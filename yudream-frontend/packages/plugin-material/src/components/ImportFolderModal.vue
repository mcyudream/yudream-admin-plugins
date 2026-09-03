<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FolderImportItem, FolderImportPayload, FolderImportResult, TagView } from '../types'
import { Progress as AProgress } from '@arco-design/web-vue'
import { FaButton, FaIcon, FaModal, FaSelect } from '@yudream/components'
import { computed, ref, watch } from 'vue'
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
const files = ref<File[]>([])
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const importing = ref(false)
const submitting = ref(false)
const completed = ref(false)
const uploadedCount = ref(0)
const activePercents = ref<Record<number, number>>({})
const pickError = ref('')
const failures = ref<Failure[]>([])

const totalSize = computed(() => files.value.reduce((sum, file) => sum + file.size, 0))
const percent = computed(() => {
  if (!files.value.length) {
    return 0
  }
  const active = Object.values(activePercents.value).reduce((sum, value) => sum + value, 0) / 100
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
    activePercents.value = {}
    pickError.value = ''
    failures.value = []
  }
})

async function pickFolder() {
  const picked = await pickDirectory()
  pickError.value = ''
  failures.value = []
  completed.value = false
  if (!picked.length) {
    return
  }
  if (picked.length > MAX_ITEMS) {
    files.value = []
    folderName.value = ''
    pickError.value = `文件夹包含 ${picked.length} 个文件，单次最多导入 ${MAX_ITEMS} 个`
    return
  }
  files.value = picked
  const relative = picked[0]?.webkitRelativePath || ''
  folderName.value = relative.includes('/') ? relative.split('/')[0] : ''
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
      const file = files.value[index]
      try {
        const object = await uploadFileWithProgress(props.sdk, file, (value) => {
          activePercents.value = { ...activePercents.value, [index]: value }
        })
        items.push({ fileId: object.id, filename: file.name })
      }
      catch (error) {
        uploadFailures.push({ filename: file.name, message: errorMessage(error) })
      }
      finally {
        const rest = { ...activePercents.value }
        delete rest[index]
        activePercents.value = rest
        uploadedCount.value += 1
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, files.value.length) }, () => worker()))
  return { items, uploadFailures }
}

async function onConfirm() {
  if (completed.value) {
    open.value = false
    return
  }
  if (!files.value.length || importing.value) {
    return
  }
  importing.value = true
  failures.value = []
  const { items, uploadFailures } = await uploadAll()
  if (!items.length) {
    failures.value = uploadFailures
    importing.value = false
    return
  }
  submitting.value = true
  const result = await props.submit({
    categoryName: folderName.value || undefined,
    tags: tags.value,
    visibility: visibility.value,
    items,
  })
  submitting.value = false
  importing.value = false
  if (!result) {
    // 请求失败：错误已由调用方 toast，保持现状允许重试（已上传文件会重新上传）
    failures.value = uploadFailures
    return
  }
  failures.value = [...uploadFailures, ...result.failures]
  if (failures.value.length) {
    // 部分失败：展示清单，确认按钮变为“完成”，避免重复确认造成重复物料
    completed.value = true
    return
  }
  open.value = false
}
</script>

<template>
  <FaModal
    v-model="open"
    title="导入文件夹"
    description="选择本地文件夹批量上传，分类自动使用文件夹名（已有同名分类则复用，否则自动创建）"
    :confirm-button-text="confirmText"
    :confirm-button-loading="importing"
    :confirm-button-disabled="!importing && !completed && !files.length"
    :show-cancel-button="!importing && !completed"
    :closable="!importing"
    :close-on-click-overlay="!importing"
    :close-on-press-escape="!importing"
    @confirm="onConfirm"
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
      <div v-if="files.length" class="max-h-32 overflow-auto rounded border border-border/60 p-2 text-xs text-secondary-foreground/70">
        <div v-for="file in files" :key="file.webkitRelativePath || file.name" class="truncate">
          {{ file.webkitRelativePath || file.name }}
        </div>
      </div>
      <template v-if="files.length">
        <div class="text-sm">
          <span class="text-secondary-foreground/80">分类：</span>
          <span>{{ folderName || '未分类' }}</span>
          <span class="text-xs text-secondary-foreground/60">（取文件夹名）</span>
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
      <div v-if="failures.length" class="flex flex-col gap-1">
        <span class="text-xs text-destructive">{{ failures.length }} 个文件未导入：</span>
        <div class="max-h-32 overflow-auto rounded border border-destructive/40 p-2 text-xs">
          <div v-for="failure in failures" :key="failure.filename" class="truncate">
            {{ failure.filename }}：{{ failure.message }}
          </div>
        </div>
      </div>
    </div>
  </FaModal>
</template>
