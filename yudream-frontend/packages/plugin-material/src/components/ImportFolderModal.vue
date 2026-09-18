<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PickedFile } from '../api/upload'
import type { DeptOption, FolderImportItem, FolderImportMode, FolderImportPayload, FolderImportResult, TagView } from '../types'
import { Progress as AProgress } from '@arco-design/web-vue'
import { FaButton, FaIcon, FaInput, FaModal, FaRadioGroup } from '@yudream/components'
import { computed, markRaw, reactive, ref, watch } from 'vue'
import { uploadFileWithProgress, pickDirectory } from '../api/upload'
import { formatSize } from '../types'
import TagPicker from './TagPicker.vue'
import VisibilityDeptField from './VisibilityDeptField.vue'

/** 与后端 FolderImportService.MAX_ITEMS 保持一致 */
const MAX_ITEMS = 200
/** 组合物料形态的子物料上限，与后端 MaterialItemService.MAX_ITEMS 一致 */
const MAX_BUNDLE_ITEMS = 50
/** 并发上传数：串行太慢，全开又容易打满浏览器连接 */
const UPLOAD_CONCURRENCY = 3

const MODE_OPTIONS = [
  {
    label: '逐个文件导入',
    value: 'FILES',
    description: '每个文件创建一个单文件物料；物料名取「子文件夹名-文件名」，文件夹名同时作为标签附加',
  },
  {
    label: '导入为单个组合物料',
    value: 'BUNDLE',
    description: '整个文件夹创建一个组合物料，每个文件成为一个子物料，子物料名取「子文件夹名-文件名」',
  },
]

interface Failure {
  filename: string
  message: string
}

const props = defineProps<{
  sdk: YuDreamPluginSdk
  tags: TagView[]
  /** 可见范围=仅部门时的部门选项（用户端为自己加入的部门） */
  deptOptions: DeptOption[]
  /** 落库请求；返回 null 表示请求失败（错误提示由调用方展示） */
  submit: (payload: FolderImportPayload) => Promise<FolderImportResult | null>
  /** 组合物料导入成功后的跳转入口；不传则不显示「查看物料」 */
  openMaterial?: (materialId: string) => void
}>()
const open = defineModel<boolean>({ required: true })

const mode = ref<FolderImportMode>('FILES')
const folderName = ref('')
/** BUNDLE 模式下的组合物料名称，选目录时预填文件夹名，可改 */
const bundleName = ref('')
const files = ref<PickedFile[]>([])
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const deptIds = ref<string[]>([])
const importing = ref(false)
const submitting = ref(false)
const completed = ref(false)
const uploadedCount = ref(0)
/** 最近一次成功提交的组合物料（id 非空表示可提供「查看物料」入口） */
const resultMaterialId = ref('')
const resultMaterialName = ref('')
/**
 * 在传文件的进度表：用 reactive 直接改键而不是每次展开整个对象。
 * 进度回调按整数百分比去重（值不变不触发渲染），文件多时避免整棵模板反复 diff。
 */
const activePercents = reactive<Record<number, number>>({})
const pickError = ref('')
/** 目录遍历时无法读取被跳过的子文件夹/文件（见 pickDirectory） */
const skippedEntries = ref<string[]>([])
const failures = ref<Failure[]>([])

const isBundle = computed(() => mode.value === 'BUNDLE')
const totalSize = computed(() => files.value.reduce((sum, entry) => sum + entry.file.size, 0))
/** 所有文件中间层子文件夹名的去重集合（根文件夹自身与文件名不计） */
const folderTagNames = computed(() => {
  const names = new Set<string>()
  for (const entry of files.value) {
    for (const segment of folderSegments(entry.path)) {
      names.add(segment)
    }
  }
  return [...names]
})
/** BUNDLE 模式下超出子物料上限：提示改用逐个文件导入，不允许提交 */
const bundleTooMany = computed(() => isBundle.value && files.value.length > MAX_BUNDLE_ITEMS)
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
const confirmDisabled = computed(() => !importing.value && !completed.value && (!files.value.length || bundleTooMany.value))
const modalDescription = computed(() => isBundle.value
  ? '整个文件夹导入为一个组合物料：子物料名由子文件夹名与文件名用「-」拼接，每个子物料随后可独立上传新版本'
  : '每个文件创建一个物料，分类统一使用文件夹名；嵌套子目录的文件夹名会拼进物料名（用「-」连接）并同时作为标签附加')
const bundleTargetName = computed(() => bundleName.value.trim() || folderName.value || '未命名组合物料')
const successText = computed(() => {
  const target = folderName.value ? `到「${folderName.value}」分类` : ''
  return isBundle.value
    ? `已创建组合物料「${resultMaterialName.value || bundleTargetName.value}」并导入 ${files.value.length} 个子物料${target}。`
    : `全部 ${files.value.length} 个文件已成功导入${target}。`
})

watch(open, (value) => {
  if (value && !importing.value) {
    mode.value = 'FILES'
    folderName.value = ''
    bundleName.value = ''
    files.value = []
    tags.value = []
    visibility.value = 'PRIVATE'
    deptIds.value = []
    completed.value = false
    uploadedCount.value = 0
    resultMaterialId.value = ''
    resultMaterialName.value = ''
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
    bundleName.value = ''
    pickError.value = `文件夹包含 ${result.files.length} 个文件，单次最多导入 ${MAX_ITEMS} 个`
    return
  }
  // File 是原生对象不会被 Vue 代理，但数组本身也不需要深响应，markRaw 省掉遍历
  files.value = markRaw(result.files)
  folderName.value = result.rootName || (result.files[0]?.path.split('/')[0] ?? '')
  bundleName.value = folderName.value
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

/** 跳到刚创建的组合物料（子物料已在导入时建好，后续版本维护在详情页完成）。 */
function openResult() {
  if (!resultMaterialId.value) {
    return
  }
  const materialId = resultMaterialId.value
  open.value = false
  props.openMaterial?.(materialId)
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
  if (!files.value.length || importing.value || bundleTooMany.value) {
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
          mode: mode.value,
          // BUNDLE 模式下 name 是组合物料名：留空交由后端回退分类名
          name: isBundle.value ? (bundleName.value.trim() || undefined) : undefined,
          categoryName: folderName.value || undefined,
          tags: tags.value,
          visibility: visibility.value,
          deptIds: visibility.value === 'DEPT' ? deptIds.value : undefined,
          items: uploaded.items,
        })
        if (!result) {
          // 请求失败：错误已由调用方 toast，保持现状允许重试（已上传文件会重新上传）
          failures.value = uploadFailures
          return
        }
        failures.value = [...uploadFailures, ...result.failures]
        resultMaterialId.value = result.materialId || ''
        resultMaterialName.value = result.materialName || ''
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
    :description="modalDescription"
    :confirm-button-text="confirmText"
    :confirm-button-loading="importing"
    :confirm-button-disabled="confirmDisabled"
    :show-cancel-button="!importing && !completed"
    :closable="!importing"
    :close-on-click-overlay="!importing"
    :close-on-press-escape="!importing"
    :before-close="handleBeforeClose"
  >
    <div class="flex flex-col gap-3">
      <FaRadioGroup v-model="mode" :options="MODE_OPTIONS" :disabled="importing || completed" />
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
      <template v-if="files.length && isBundle">
        <span v-if="bundleTooMany" class="text-xs text-destructive">
          组合物料最多 {{ MAX_BUNDLE_ITEMS }} 个子物料，当前 {{ files.length }} 个文件，请改用逐个文件导入。
        </span>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">组合物料名称</span>
          <FaInput v-model="bundleName" :disabled="importing" :placeholder="folderName || '未命名组合物料'" />
        </label>
        <span class="text-xs text-secondary-foreground/60">
          {{ files.length }} 个文件将作为「{{ bundleTargetName }}」的子物料，子物料名取「子文件夹名-文件名」（去扩展名），各自从 v1 开始独立版本。
        </span>
        <span v-if="folderTagNames.length" class="text-xs text-secondary-foreground/60">
          子文件夹 {{ folderTagNames.slice(0, 6).join('、') }}{{ folderTagNames.length > 6 ? ' 等' : '' }} 中的文件同样成为子物料：目录名拼进子物料名，来源目录同时记录在该子物料的版本备注里。
        </span>
      </template>
      <template v-if="files.length && !isBundle">
        <div class="text-sm">
          <span class="text-secondary-foreground/80">分类：</span>
          <span>{{ folderName || '未分类' }}</span>
          <span class="text-xs text-secondary-foreground/60">（取文件夹名）</span>
        </div>
        <div v-if="folderTagNames.length" class="text-sm">
          <span class="text-secondary-foreground/80">层级命名：</span>
          <span class="text-xs text-secondary-foreground/60">
            子文件夹 {{ folderTagNames.slice(0, 6).join('、') }}{{ folderTagNames.length > 6 ? ' 等' : '' }} 会拼进物料名（如「横版-封面」），并同时作为标签附加到其中的文件
          </span>
        </div>
      </template>
      <label v-if="files.length" class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">{{ isBundle ? '标签（应用到组合物料）' : '标签（应用到全部文件）' }}</span>
        <TagPicker v-model="tags" :tags="props.tags" />
      </label>
      <VisibilityDeptField v-if="files.length" v-model:visibility="visibility" v-model:dept-ids="deptIds" :dept-options="props.deptOptions" :disabled="importing" />
      <div v-if="importing" class="flex flex-col gap-1">
        <AProgress :percent="percent" />
        <span class="text-xs text-secondary-foreground/70">已处理 {{ uploadedCount }}/{{ files.length }} 个文件</span>
      </div>
      <div v-if="completed && !failures.length" class="flex items-center gap-1 text-xs text-secondary-foreground/70">
        <FaIcon name="i-ri:checkbox-circle-line" />{{ successText }}
      </div>
      <div v-if="failures.length" class="flex flex-col gap-1">
        <span class="text-xs text-destructive">{{ isBundle ? `${failures.length} 个文件未导入为子物料：` : `${failures.length} 个文件未导入：` }}</span>
        <!-- 同名文件可能来自不同子文件夹，用索引做 key 避免冲突；v-memo 隔断进度刷新 -->
        <div v-memo="[failures]" class="max-h-32 overflow-auto rounded border border-destructive/40 p-2 text-xs">
          <div v-for="(failure, index) in failures" :key="index" class="truncate">
            {{ failure.filename }}：{{ failure.message }}
          </div>
        </div>
      </div>
      <div v-if="completed && resultMaterialId && props.openMaterial" class="flex items-center gap-2">
        <FaButton variant="outline" size="sm" @click="openResult">
          <FaIcon name="i-ri:external-link-line" />查看物料
        </FaButton>
        <span class="text-xs text-secondary-foreground/60">子物料已在导入时建好，后续版本维护在物料详情页完成。</span>
      </div>
    </div>
  </FaModal>
</template>
