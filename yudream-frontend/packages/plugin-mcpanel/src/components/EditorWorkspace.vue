<script setup lang="ts">
import { FaAlert, FaButton, FaDropdown, FaIcon, FaSwitch, useFaModal, useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useEditorTabs } from '../composables/useEditorTabs'
import type { EditorLoadResult } from '../composables/useEditorTabs'
import { EDITOR_ENCODINGS } from '../utils/fileContent'
import EditorCodePane from './EditorCodePane.vue'
import EditorEmptyState from './EditorEmptyState.vue'
import EditorFileTabs from './EditorFileTabs.vue'
import FileBrowserPanel from './FileBrowserPanel.vue'
import type { BrowserListResult } from './FileBrowserPanel.vue'

/**
 * 弹窗/嵌入式文件编辑工作区：
 * - 左侧目录面板独立懒加载（有界目录页 + 截断提示），加载失败不影响正文；
 * - 顶部多文件标签、正文行号窗格、底部固定保存栏；
 * - 首开不等待目录：初始文件（query path）优先加载，目录面板独立请求；
 * - 编码：保留原始字节，支持"以编码重新打开"（重新解码显示）与
 *   "以编码保存"（面板侧转码落盘，仅允许白名单编码）；
 * - 脏标签关闭确认 + beforeunload 保护 + 暴露 confirmLeave() 给路由守卫；
 * - 窄屏（<1024px）目录收起为可切换浮层。
 * 读写回调由宿主页面注入（实例 / 节点两种通道复用）。
 */
const props = withDefaults(defineProps<{
  load: (path: string, hints: { size?: number }) => Promise<EditorLoadResult>
  save: (path: string, text: string, charset: string) => Promise<void>
  canSave?: boolean
  /** 目录列表（有界 + total）；不传则隐藏左侧目录。 */
  listDir?: (path: string, keyword?: string) => Promise<BrowserListResult>
  /** 初始要打开的文件路径（空 = 只展示空态，可选文件）。 */
  initialPath?: string
  /** 文件目录初始路径（返回文件列表后保持原目录）。 */
  initialDirectory?: string
  /** 填满父容器高度（弹窗内使用）。 */
  fill?: boolean
}>(), {
  canSave: true,
  initialPath: '',
  initialDirectory: '',
  fill: false,
})

const emit = defineEmits<{ saved: [path: string] }>()

const toast = useFaToast()
const modal = useFaModal()
const sideOpen = ref(false)
const directoryReady = ref(!props.initialPath)
const wrap = ref(false)

function confirmDiscard(names: string[]): Promise<boolean> {
  return new Promise((resolve) => {
    modal.confirm({
      title: '放弃未保存的修改？',
      content: `「${names.join('、')}」有未保存的修改，丢弃后无法恢复。`,
      confirmButtonText: '丢弃并继续',
      cancelButtonText: '留在本页',
      closeOnClickOverlay: false,
      closeOnPressEscape: false,
      onConfirm: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}

const editor = useEditorTabs({
  load: props.load,
  save: props.save,
  canSave: () => props.canSave,
  confirmDiscard,
  onNotify: (type, message) => {
    if (type === 'success') {
      toast.success(message)
    }
    else if (type === 'warning') {
      toast.warning(message)
    }
    else {
      toast.error(message)
    }
  },
  onSaved: path => emit('saved', path),
})

const active = computed(() => editor.active.value)
const isDirty = computed(() => !!active.value && editor.isDirty(active.value))
const lineCount = computed(() => (active.value ? active.value.text.split('\n').length : 0))
const charCount = computed(() => (active.value ? active.value.text.length : 0))

const encodingLabel = computed(() => (active.value?.encoding ?? 'utf-8').toUpperCase())
const convertingTo = computed(() => {
  const tab = active.value
  return tab && tab.encoding !== tab.savedEncoding ? tab.encoding.toUpperCase() : ''
})

interface EncodingMenuItem {
  label: string
  icon?: string
  handle?: () => void
  items?: EncodingMenuItem[][]
}

/** 编码菜单：重新解码显示（用保留的原始字节，不重读）与保存时转码两组子菜单。 */
const encodingMenu = computed<EncodingMenuItem[][]>(() => {
  const tab = active.value
  if (!tab) {
    return []
  }
  const reopenItems = EDITOR_ENCODINGS.map(item => ({
    label: item.label,
    icon: !tab.error && tab.encoding === item.value ? 'i-ri:check-line' : 'i-ri:file-line',
    handle: () => void editor.reopenWithEncoding(tab, item.value),
  }))
  const saveItems = EDITOR_ENCODINGS.map(item => ({
    label: item.label,
    icon: tab.encoding === item.value && !tab.error ? 'i-ri:check-line' : 'i-ri:save-line',
    handle: () => editor.setSaveEncoding(tab, item.value),
  }))
  return [
    [{ label: '以此编码重新打开', items: [reopenItems] }],
    [{ label: '以此编码保存（转换）', items: [saveItems] }],
  ]
})

/** 解码失败错误页用的"换编码重开"菜单。 */
const reopenMenu = computed<EncodingMenuItem[][]>(() => {
  const tab = active.value
  if (!tab) {
    return []
  }
  return [EDITOR_ENCODINGS.map(item => ({
    label: item.label,
    icon: 'i-ri:file-line',
    handle: () => void editor.reopenWithEncoding(tab, item.value),
  }))]
})

const LANG_MAP: Record<string, string> = {
  yml: 'YAML',
  yaml: 'YAML',
  json: 'JSON',
  properties: 'Properties',
  xml: 'XML',
  html: 'HTML',
  css: 'CSS',
  js: 'JavaScript',
  ts: 'TypeScript',
  sh: 'Shell',
  bat: 'Batch',
  md: 'Markdown',
  txt: '文本',
  log: '日志',
  ini: 'INI',
  conf: 'Conf',
  toml: 'TOML',
}

const langLabel = computed(() => {
  const ext = active.value?.path.split('.').pop()?.toLowerCase() ?? ''
  return LANG_MAP[ext] || (ext ? ext.toUpperCase() : '文本')
})

function openFile(path: string, size?: number) {
  sideOpen.value = false
  return editor.openFile(path, { size })
}

const confirmLeave = editor.confirmLeave

function onTreeFile(path: string, entry: { size?: number }) {
  void openFile(path, entry.size)
}

// beforeunload：有脏内容时浏览器级拦截。
function onBeforeUnload(event: BeforeUnloadEvent) {
  if (editor.hasDirty.value || editor.saving.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

function openDirectory() {
  directoryReady.value = true
  sideOpen.value = true
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  if (props.initialPath) {
    void editor.openFile(props.initialPath).finally(() => { directoryReady.value = true })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})

// 初始文件变化（路由 query 切换）时打开对应标签。
watch(() => props.initialPath, (path) => {
  if (path) {
    void editor.openFile(path)
  }
})

defineExpose({
  openFile,
  confirmLeave,
  hasDirty: computed(() => editor.hasDirty.value),
  reset: editor.reset,
  reload: () => {
    const tab = active.value
    if (tab) {
      editor.retryLoad(tab)
    }
  },
})
</script>

<template>
  <div class="mced" :class="{ 'is-fill': fill, 'is-side-open': sideOpen }">
    <div v-if="props.listDir && directoryReady" class="mced-side">
      <div class="mced-side-head"><span>文件浏览</span><FaButton size="icon-sm" variant="ghost" title="收起目录" @click="sideOpen = false"><FaIcon name="i-ri:close-line" /></FaButton></div>
      <FileBrowserPanel
        :list="props.listDir"
        :open-file="onTreeFile"
        :initial-path="initialDirectory"
        title="目录"
      />
    </div>

    <div class="mced-main">
      <div class="mced-toolbar">
        <FaButton
          v-if="props.listDir"
          size="icon-sm"
          variant="ghost"
          class="mced-side-toggle"
          title="打开目录面板"
          @click="openDirectory"
        >
          <FaIcon name="i-ri:side-bar-line" />
        </FaButton>
        <EditorFileTabs
          :tabs="editor.tabs.value"
          :active-path="editor.activePath.value"
          @select="(path) => { editor.activePath.value = path }"
          @close="path => void editor.closeTab(path)"
        />
      </div>

      <template v-if="active">
        <div v-if="active.error" class="mced-body is-empty">
          <div class="flex flex-col items-center gap-3 text-center">
            <FaIcon name="i-ri:error-warning-line" class="text-3xl opacity-50" />
            <FaAlert variant="destructive" :title="`读取失败：${active.name}`" :description="active.error" class="max-w-[36rem]" />
            <div class="mced-error-actions">
              <FaButton size="sm" variant="outline" :disabled="active.loading" @click="editor.retryLoad(active)">
                重试加载
              </FaButton>
              <FaDropdown :items="reopenMenu" align="center">
                <FaButton size="sm" variant="outline">
                  <FaIcon name="i-ri:translate-2" />换编码重新打开
                </FaButton>
              </FaDropdown>
            </div>
          </div>
        </div>
        <div v-else-if="active.loading" class="mced-body is-empty">
          <div class="flex flex-col items-center gap-2">
            <FaIcon name="i-line-md:loading-twotone-loop" class="text-2xl opacity-60 mced-spin" />
            <div class="opacity-80">正在读取文件内容…（{{ active.loadingSeconds }}s）</div>
            <div class="text-xs opacity-50">读取设有客户端截止时间，超时会转为错误提示</div>
          </div>
        </div>
        <EditorCodePane
          v-else
          :key="active.path"
          :tab="active"
          :wrap="wrap"
          @update:text="active.text = $event"
          @save="() => void editor.save()"
        />
      </template>
      <EditorEmptyState v-else />

      <div class="mced-savebar">
        <span class="mced-path" :title="active?.path">{{ active?.path || '未打开文件' }}</span>
        <span v-if="active && !active.error" class="mced-meta">
          {{ langLabel }} · {{ lineCount }} 行 · {{ charCount }} 字符
        </span>
        <span v-if="isDirty" class="mced-chip is-dirty">未保存</span>
        <span v-if="convertingTo" class="mced-chip is-convert" :title="`保存时将转换为 ${convertingTo}`">
          将转为 {{ convertingTo }}
        </span>
        <span v-if="active?.truncated" class="mced-chip is-ro">只读预览</span>
        <span v-if="active?.saveFailed" class="mced-chip is-failed" :title="active.saveFailed">
          上次保存未确认
        </span>
        <span class="mced-spacer" />
        <FaDropdown v-if="active && !active.error && !active.loading" :items="encodingMenu" side="top" align="end">
          <FaButton size="sm" variant="ghost" class="mced-encoding-btn" title="编码：重新打开按所选编码重新解码；保存时转换由面板转码落盘">
            {{ encodingLabel }}<FaIcon name="i-ri:arrow-down-s-line" />
          </FaButton>
        </FaDropdown>
        <label class="mced-wrap">
          自动换行
          <FaSwitch v-model="wrap" />
        </label>
        <FaButton size="sm" variant="outline" :disabled="!active || active.loading || editor.saving.value" @click="() => active && editor.retryLoad(active)">
          <FaIcon name="i-ri:refresh-line" />重新读取
        </FaButton>
        <FaButton
          size="sm"
          variant="outline"
          :disabled="!active || editor.saving.value"
          @click="() => active && void editor.closeTab(active.path)"
        >
          关闭标签
        </FaButton>
        <FaButton
          v-if="active && !active.readOnly"
          size="sm"
          :loading="editor.saving.value"
          :disabled="!isDirty || !!active.error"
          @click="() => void editor.save()"
        >
          <FaIcon name="i-ri:save-line" />
          保存
        </FaButton>
      </div>
      <div v-if="active?.saveFailed" class="mced-savebar-note">
        {{ active.saveFailed }}
      </div>
    </div>
  </div>
</template>
