<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CategoryView, DeptOption, TagView } from '../types'
import { FaFileUpload, FaInput, FaModal, FaRadioGroup } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { uploadFileWithProgress } from '../api/upload'
import CategoryPicker from './CategoryPicker.vue'
import TagPicker from './TagPicker.vue'
import VisibilityDeptField from './VisibilityDeptField.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  categories: CategoryView[]
  tags: TagView[]
  /** 可见范围=仅部门时的部门选项（用户端为自己加入的部门） */
  deptOptions: DeptOption[]
  /** 选择分类时就地新增分类（同名会复用已有分类） */
  createCategory?: (name: string) => Promise<CategoryView | null>
  saving?: boolean
}>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [{ fileId: string, filename: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }] }>()

/**
 * 物料分两种形态：单文件物料自带主文件并按主文件版本管理；
 * 组合物料不带主文件，创建后在详情页上传任意多个子物料（如「明信片」下挂原图/设计稿/成图）。
 */
const modeOptions = [
  { label: '单文件物料', value: 'single' },
  { label: '组合物料', value: 'bundle' },
]

const mode = ref<'single' | 'bundle'>('single')
const files = ref<FileItem[]>([])
const uploaded = ref<YuDreamPluginFileObject | null>(null)
const name = ref('')
const categoryId = ref('')
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const deptIds = ref<string[]>([])
const uploadError = ref('')

const isBundle = computed(() => mode.value === 'bundle')
const canConfirm = computed(() => isBundle.value ? !!name.value.trim() : !!uploaded.value)

watch(open, (value) => {
  if (value) {
    mode.value = 'single'
    files.value = []
    uploaded.value = null
    name.value = ''
    categoryId.value = ''
    tags.value = []
    visibility.value = 'PRIVATE'
    deptIds.value = []
    uploadError.value = ''
  }
})

/** 切到组合物料时文件不再需要，清掉避免提交携带陈旧 fileId */
watch(mode, () => {
  files.value = []
  uploaded.value = null
  uploadError.value = ''
})

async function httpRequest(options: FileUploadRequestOptions) {
  // 插件 HTTP 通道不支持 multipart，借宿主文件上传接口拿 fileId 再交给插件落库；XHR 版带进度且不受宿主 60s 超时限制
  return uploadFileWithProgress(props.sdk, options.file, options.onProgress)
}

function onSuccess(response: YuDreamPluginFileObject, file: File) {
  uploaded.value = response
  if (!name.value.trim()) {
    name.value = file.name.replace(/\.[^.]+$/, '')
  }
}

function onConfirm() {
  if (isBundle.value && !name.value.trim()) {
    uploadError.value = '组合物料需要填写名称'
    return
  }
  if (!isBundle.value && !uploaded.value?.id) {
    uploadError.value = '请先选择并上传文件'
    return
  }
  emit('submit', {
    fileId: isBundle.value ? '' : (uploaded.value?.id || ''),
    filename: isBundle.value ? '' : (uploaded.value?.originalName || files.value[0]?.name || 'file'),
    name: name.value.trim(),
    categoryId: categoryId.value,
    tags: tags.value,
    visibility: visibility.value,
    deptIds: visibility.value === 'DEPT' ? deptIds.value : [],
  })
}
</script>

<template>
  <FaModal
    v-model="open"
    title="上传物料"
    description="单文件物料支持图片、PPT、Excel、PSD、视频、音频等常见格式；组合物料可挂多个子物料"
    :confirm-button-loading="saving"
    :confirm-button-disabled="!canConfirm"
    @confirm="onConfirm"
  >
    <div class="flex flex-col gap-3">
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">物料形态</span>
        <FaRadioGroup v-model="mode" :options="modeOptions" />
      </label>
      <p v-if="isBundle" class="material-hint">
        组合物料本身不带文件，创建后可在物料详情页上传任意多个子物料（如「明信片」下挂原图、设计稿、成图），每个子物料独立管理版本。
      </p>
      <template v-else>
        <FaFileUpload
          v-model="files"
          :max="1"
          :http-request="httpRequest"
          description="拖放或点击选择文件"
          @on-success="onSuccess"
        />
      </template>
      <span v-if="uploadError" class="text-xs text-destructive">{{ uploadError }}</span>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">名称<template v-if="isBundle">（必填）</template></span>
        <FaInput v-model="name" :placeholder="isBundle ? '如「明信片」「品牌 Logo」' : '留空则使用文件名'" maxlength="120" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">分类</span>
        <CategoryPicker v-model="categoryId" :categories="props.categories" :create="props.createCategory" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">标签（最多 8 个）</span>
        <TagPicker v-model="tags" :tags="props.tags" />
      </label>
      <VisibilityDeptField v-model:visibility="visibility" v-model:dept-ids="deptIds" :dept-options="props.deptOptions" />
    </div>
  </FaModal>
</template>
