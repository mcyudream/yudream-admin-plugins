<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PickedFile } from '../api/upload'
import type { FolderImportItem, FolderImportPayload, FolderImportResult, TagView } from '../types'
import { Progress as AProgress } from '@arco-design/web-vue'
import { FaButton, FaIcon, FaModal, FaSelect } from '@yudream/components'
import { computed, markRaw, reactive, ref, watch } from 'vue'
import { uploadFileWithProgress, pickDirectory } from '../api/upload'
import { formatSize, VISIBILITY_OPTIONS } from '../types'
import TagPicker from './TagPicker.vue'

/** 与后端 FolderImportService.MAX_ITEMS 保持一致 */
const MAX_ITEMS = 200
/** 并发上传数：串行太慢，全开又容易打满浏览器连接 */
const UPLOAD_CONCURRENCY = 3

interface Failure {
  filename: string
  message: string
}

const props = defineProps<{
  sdk: YuDreamPluginSdk
  tags: TagView[]
  /** 落库请求；返回 null 表示请求失败（错误提示由调用方展示） */
  submit: (payload: FolderImportPayload) => Promise<FolderImportResult | null>
}>()
const open = defineModel<boolean>({ required: true })

const folderName = ref('')
const files = ref<PickedFile[]>([])
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const importing = ref(false)
const submitting = ref(false)
const completed = ref(false)
const uploadedCount = ref(0)
/**
 * 在传文件的进度表：用 reactive 直接改键而不是每次展开整个对象。
 * 进度回调按整数百分比去重（值不变不触发渲染），文件多时避免整棵模板反复 diff。
 */
const activePercents = reactive<Record<number, number>>({})
const pickError = ref('')
/** 目录遍历时无法读取被跳过的子文件夹/文件（见 pickDirectory） */
const skippedEntries = ref<string[]>([])
const failures = ref<Failure[]>([])

const totalSize = computed(() => files.value.reduce((sum, entry) => sum + entry.file.size, 0))
/** 所有文件中间层子文件夹名的去重集合（根文件夹自身与文件名不计），用于「将作为标签附加」摘要展示 */
const folderTagNames = computed(() => {
  const names = new Set<string>()
  for (const entry of files.value) {
    for (const segment of folderSegments(entry.path)) {
      names.add(segment)
    }
  }
  return [...names]
})
const percent = computed(() => {
  if (!files.value.length) {
    return 0
  }
  const active = Object.values(activePercents).reduce((sum, value) => sum + value, 0) / 100
  return Math.min(1, (uploadedCount.value + active) / files.value.length)
})
const confirmText = computed(() => {
  if (importing.value) {
    return submitting.value ? '正在创建物料记录…' : '正在上传…'
  }
  return completed.value ? '完成' : '开始导入'
})

watch(open, (value) => {
  if (value && !importing.value) {
    folderName.value = ''
    files.value = []
    tags.value = []
    visibility.value = 'PRIVATE'
    completed.value = false
    uploadedCount.value = 0
    for (const key of Object.keys(activePercents)) {
      delete activePercents[Number(key)]
    }
    pickError.value = ''
    skippedEntries.value = []
    failures.value = []
  }
})

async function pickFolder() {
  const result = await pickDirectory()
  pickError.value = ''
  skippedEntries.value = result.skipped
  failures.value = []
  completed.value = false
  if (!result.files.length) {
    return
  }
  if (result.files.length > MAX_ITEMS) {
    files.value = []
    folderName.value = ''
    pickError.value = `文件夹包含 ${result.files.length} 个文件，单次最多导入 ${MAX_ITEMS} 个`
    return
  }
  // File 是原生对象不会被 Vue 代理，但数组本身也不需要深响应，markRaw 省掉遍历
  files.value = markRaw(result.files)
  folderName.value = result.rootName || (result.files[0]?.path.split('/')[0] ?? '')
}

/** 文件的中间层子文件夹路径段：宣传物料/海报/横版/a.png → [海报, 横版]；根目录直下文件为空 */
function folderSegments(path: string): string[] {
  const segments = path.split('/')
  return segments.length > 2 ? segments.slice(1, -1) : []
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '上传失败'
}

async function uploadAll() {
  const items: FolderImportItem[] = []
  const uploadFailures: Failure[] = []
  let cursor = 0
  async function worker() {
    while (cursor < files.value.length) {
      const index = cursor++
      const entry = files.value[index]
      const file = entry.file
      try {
        const object = await uploadFileWithProgress(props.sdk, file, (value) => {
          activePercents[index] = value
        })
        const folderTags = folderSegments(entry.path)
        items.push({ fileId: object.id, filename: file.name, tags: folderTags.length ? folderTags : undefined })
      }
      catch (error) {
        // 前端上传失败展示相对路径，嵌套子文件夹中的同名文件可区分（后端失败仍只带文件名）
        uploadFailures.push({ filename: entry.path, message: errorMessage(error) })
      }
      finally {
        delete activePercents[index]
        uploadedCount.value += 1
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, files.value.length) }, () => worker()))
  return { items, uploadFailures }
}

/**
 * FaModal 默认点确认立即关窗、之后才触发 confirm 事件，导入会在后台静默进行；
 * 用 beforeClose 把导入全程留在弹窗内：confirm 跑完导入但不调 done()，停留完成态展示结果，
 * 由用户再点「完成」或 X 才真正关窗。处理函数绝不能抛出（组件内部没有 finally，loading 会卡死）。
 */
async function handleBeforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    // 取消/关闭：导入中拦截（界面上 X、遮罩、Esc 此时也已禁用），空闲直接放行
    if (!importing.value) {
      done()
    }
    return
  }
  if (completed.value) {
    done()
    return
  }
  if (!files.value.length || importing.value) {
    return
  }
  importing.value = true
  failures.value = []
  let uploadFailures: Failure[] = []
  try {
    const uploaded = await uploadAll()
    uploadFailures = uploaded.uploadFailures
    if (uploaded.items.length) {
      submitting.value = true
      try {
        const result = await props.submit({
          categoryName: folderName.value || undefined,
          tags: tags.value,
          visibility: visibility.value,
          items: uploaded.items,
        })
        if (!result) {
          // 请求失败：错误已由调用方 toast，保持现状允许重试（已上传文件会重新上传）
          failures.value = uploadFailures
          return
        }
        failures.value = [...uploadFailures, ...result.failures]
        // 无论全部成功还是部分失败都停留完成态展示结果（成功摘要/失败清单），
        // 确认键变「完成」由用户自行关闭——全部成功就自动关窗会让人来不及看到进度与结果
        completed.value = true
      }
      finally {
        submitting.value = false
      }
    }
    else {
      failures.value = uploadFailures
    }
  }
  catch (error) {
    // 兜底：任何意外异常都落进失败清单，绝不能向外抛（会卡死弹窗的确认按钮 loading）
    failures.value = [...uploadFailures, { filename: '（导入流程）', message: errorMessage(error) }]
  }
  finally {
    importing.value = false
  }
}
</script>

<template>
  <FaModal
    v-model="open"
    title="导入文件夹"
    description="选择本地文件夹批量上传，分类统一使用文件夹名；子文件夹名自动作为标签附加到其中的文件，保留目录层级"
    :confirm-button-text="confirmText"
    :confirm-button-loading="importing"
    :confirm-button-disabled="!importing && !completed && !files.length"
    :show-cancel-button="!importing && !completed"
    :closable="!importing"
    :close-on-click-overlay="!importing"
    :close-on-press-escape="!importing"
    :before-close="handleBeforeClose"
  >
    <div class="flex flex-col gap-3">
      <div class="flex items-center gap-2">
        <FaButton variant="outline" :disabled="importing" @click="pickFolder">
          <FaIcon name="i-ri:folder-upload-line" />选择文件夹
        </FaButton>
        <span v-if="files.length" class="text-sm text-secondary-foreground/80">
          {{ folderName || '所选文件夹' }}：{{ files.length }} 个文件，共 {{ formatSize(totalSize) }}
        </span>
      </div>
      <span v-if="pickError" class="text-xs text-destructive">{{ pickError }}</span>
      <span v-if="skippedEntries.length" class="text-xs text-secondary-foreground/70">
        {{ skippedEntries.length }} 个子文件夹或文件无法读取，已跳过：{{ skippedEntries.slice(0, 3).join('、') }}{{ skippedEntries.length > 3 ? ' 等' : '' }}
      </span>
      <!-- v-memo：上传进度刷新时跳过整份文件清单的 diff，清单内容只随 files 变化 -->
      <div v-if="files.length" v-memo="[files]" class="max-h-32 overflow-auto rounded border border-border/60 p-2 text-xs text-secondary-foreground/70">
        <div v-for="entry in files" :key="entry.path" class="truncate">
          {{ entry.path }}
        </div>
      </div>
      <template v-if="files.length">
        <div class="text-sm">
          <span class="text-secondary-foreground/80">分类：</span>
          <span>{{ folderName || '未分类' }}</span>
          <span class="text-xs text-secondary-foreground/60">（取文件夹名）</span>
        </div>
        <div v-if="folderTagNames.length" class="text-sm">
          <span class="text-secondary-foreground/80">层级标签：</span>
          <span class="text-xs text-secondary-foreground/60">
            子文件夹 {{ folderTagNames.slice(0, 6).join('、') }}{{ folderTagNames.length > 6 ? ' 等' : '' }} 将作为标签附加到其中的文件
          </span>
        </div>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">标签（应用到全部文件）</span>
          <TagPicker v-model="tags" :tags="props.tags" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">可见范围</span>
          <FaSelect v-model="visibility" :options="VISIBILITY_OPTIONS" :disabled="importing" />
        </label>
      </template>
      <div v-if="importing" class="flex flex-col gap-1">
        <AProgress :percent="percent" />
        <span class="text-xs text-secondary-foreground/70">已处理 {{ uploadedCount }}/{{ files.length }} 个文件</span>
      </div>
      <div v-if="completed && !failures.length" class="flex items-center gap-1 text-xs text-secondary-foreground/70">
        <FaIcon name="i-ri:checkbox-circle-line" />全部 {{ files.length }} 个文件已成功导入{{ folderName ? '到「' + folderName + '」分类' : '' }}。
      </div>
      <div v-if="failures.length" class="flex flex-col gap-1">
        <span class="text-xs text-destructive">{{ failures.length }} 个文件未导入：</span>
        <!-- 同名文件可能来自不同子文件夹，用索引做 key 避免冲突；v-memo 隔断进度刷新 -->
        <div v-memo="[failures]" class="max-h-32 overflow-auto rounded border border-destructive/40 p-2 text-xs">
          <div v-for="(failure, index) in failures" :key="index" class="truncate">
            {{ failure.filename }}：{{ failure.message }}
          </div>
        </div>
      </div>
    </div>
  </FaModal>
</template>
