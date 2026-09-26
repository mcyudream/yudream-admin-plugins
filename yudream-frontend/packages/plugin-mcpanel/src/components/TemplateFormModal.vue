<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaInput, FaModal, FaSelect, FaSwitch, FaTextarea } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcPanelExtra } from '../api/api-extra'
import { errorMessage } from '../composables/utils'

/**
 * 模板创建/编辑弹窗：服务端模板（installer + startup）与镜像模板双形态。
 * 注入开关（authlib/时长插件）仅服务端模板有效；下载源在「面板设置」配置。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk, template: Record<string, unknown> | null }>()

const emit = defineEmits<{ saved: [template: Record<string, unknown>] }>()
const open = defineModel<boolean>('open', { default: false })
const extra = createMcPanelExtra(props.sdk)

const submitting = ref(false)
const formError = ref('')

const SERVER_KINDS = ['vanilla', 'paper', 'purpur', 'folia', 'fabric', 'forge', 'neoforge', 'quilt', 'velocity', 'bungee', 'bedrock', 'generic']

const form = reactive({
  key: '',
  kind: 'server',
  serverKind: 'paper',
  name: '',
  mcVersion: '',
  image: 'eclipse-temurin:21-jre',
  jarGlob: 'server.jar',
  jvmOpts: '-Xms512M -Xmx2G',
  installerType: 'download',
  installerUrl: '',
  injectAuthlib: true,
  injectPlaytime: true,
})

const editing = computed(() => props.template != null)

const serverKindOptions = SERVER_KINDS.map(kind => ({ label: kind, value: kind }))

watch(open, (visible) => {
  if (!visible) {
    return
  }
  formError.value = ''
  const source = props.template
  form.key = source ? String(source.key ?? '') : ''
  form.kind = source ? (String(source.kind ?? 'server') === 'image' ? 'image' : 'server') : 'server'
  form.serverKind = source && SERVER_KINDS.includes(String(source.kind)) ? String(source.kind) : 'paper'
  form.name = source ? String(source.name ?? '') : ''
  form.mcVersion = source ? String(source.mcVersion ?? '') : ''
  form.image = source ? String(source.image ?? '') : 'eclipse-temurin:21-jre'
  const startup = (source?.startup ?? {}) as Record<string, unknown>
  form.jarGlob = String(startup.jarGlob ?? 'server.jar')
  form.jvmOpts = String(startup.jvmOpts ?? '-Xms512M -Xmx2G')
  const installer = (source?.installer ?? {}) as Record<string, unknown>
  form.installerType = String(installer.type ?? 'download')
  form.installerUrl = String(installer.url ?? '')
  const inject = (source?.inject ?? {}) as Record<string, unknown>
  form.injectAuthlib = inject.authlib !== false
  form.injectPlaytime = inject.playtime !== false
})

function validate(): string {
  if (!/^[a-z0-9][a-z0-9-]{1,63}$/.test(form.key)) {
    return 'Key 只允许小写字母数字与短横线（2-64 位）'
  }
  if (!form.name.trim()) {
    return '请输入模板名称'
  }
  if (form.kind === 'image' && !form.image.trim()) {
    return '镜像模板必须填写镜像名'
  }
  if (form.kind === 'server' && form.installerType === 'download' && !form.installerUrl.trim()) {
    return '下载型安装源必须填写 URL'
  }
  return ''
}

function buildPayload() {
  if (form.kind === 'image') {
    return { key: form.key, kind: 'image', name: form.name.trim(), image: form.image.trim() }
  }
  return {
    key: form.key,
    kind: form.serverKind,
    name: form.name.trim(),
    mcVersion: form.mcVersion.trim() || null,
    image: form.image.trim(),
    startup: { jarGlob: form.jarGlob.trim() || 'server.jar', jvmOpts: form.jvmOpts.trim() },
    installer: form.installerType === 'download'
      ? { type: 'download', url: form.installerUrl.trim() }
      : { type: 'api' },
    inject: { authlib: form.injectAuthlib, playtime: form.injectPlaytime },
  }
}

function beforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  formError.value = validate()
  if (formError.value) {
    return
  }
  void submit(done)
}

async function submit(done: () => void) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    const saved = await extra.saveTemplate(buildPayload()) as Record<string, unknown>
    done()
    emit('saved', saved)
  }
  catch (error) {
    formError.value = errorMessage(error, '保存失败，请稍后重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <FaModal
    v-model="open"
    :title="editing ? '编辑模板' : '新建模板'"
    :show-cancel-button="true"
    :confirm-button-text="editing ? '保存修改' : '创建模板'"
    :confirm-button-loading="submitting"
    class="max-w-[min(42rem,calc(100vw-2rem))]"
    :before-close="beforeClose"
  >
    <div class="mcp-form">
      <label class="mcp-form-item">
        <span class="mcp-form-label">模板 Key</span>
        <FaInput v-model="form.key" :disabled="editing" clearable placeholder="例如 paper-1-21" class="w-full font-mono" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">模板名称</span>
        <FaInput v-model="form.name" clearable placeholder="例如 Paper 1.21 插件服" class="w-full" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">模板形态</span>
        <FaSelect v-model="form.kind" :options="[{ label: '服务端模板（含安装源与注入）', value: 'server' }, { label: '镜像模板（仅容器镜像）', value: 'image' }]" class="w-full" />
      </label>
      <template v-if="form.kind === 'server'">
        <label class="mcp-form-item">
          <span class="mcp-form-label">服务端类型</span>
          <FaSelect v-model="form.serverKind" :options="serverKindOptions" class="w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">默认 MC 版本（可选）</span>
          <FaInput v-model="form.mcVersion" clearable placeholder="例如 1.21.4" class="w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">运行镜像</span>
          <FaInput v-model="form.image" clearable class="w-full font-mono" />
        </label>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label class="mcp-form-item">
            <span class="mcp-form-label">服务端 jar 文件名</span>
            <FaInput v-model="form.jarGlob" clearable class="w-full font-mono" />
          </label>
          <label class="mcp-form-item">
            <span class="mcp-form-label">JVM 参数</span>
            <FaInput v-model="form.jvmOpts" clearable class="w-full font-mono" />
          </label>
        </div>
        <label class="mcp-form-item">
          <span class="mcp-form-label">安装源</span>
          <FaSelect v-model="form.installerType" :options="[{ label: '直接下载（URL 模板）', value: 'download' }, { label: '构建 API（Paper/Purpur 等）', value: 'api' }]" class="w-full" />
        </label>
        <label v-if="form.installerType === 'download'" class="mcp-form-item">
          <span class="mcp-form-label">下载 URL</span>
          <FaTextarea v-model="form.installerUrl" :rows="2" placeholder="https://mirror.example/paper.jar" class="w-full font-mono" />
        </label>
        <div class="mcp-form-item">
          <span class="mcp-form-label">注入</span>
          <div class="flex flex-wrap items-center gap-4">
            <label class="flex items-center gap-2 text-sm">
              <FaSwitch v-model="form.injectAuthlib" />
              authlib-injector（javaagent）
            </label>
            <label class="flex items-center gap-2 text-sm">
              <FaSwitch v-model="form.injectPlaytime" />
              时长记录插件（Bukkit 系）
            </label>
          </div>
          <span class="mcp-form-hint">下载源与全局开关在「面板设置」配置；加载器核心自动跳过时长插件注入。</span>
        </div>
      </template>
      <FaAlert v-if="formError" variant="destructive" title="无法保存">
  <template #description>
        {{ formError }}
  </template>
      </FaAlert>
    </div>
  </FaModal>
</template>
