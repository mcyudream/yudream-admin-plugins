<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CategoryView, DeptOption, TagView } from '../types'
import { FaFileUpload, FaInput, FaModal } from '@yudream/components'
import { ref, watch } from 'vue'
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
  saving?: boolean
}>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [{ fileId: string, filename: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }] }>()

const files = ref<FileItem[]>([])
const uploaded = ref<YuDreamPluginFileObject | null>(null)
const name = ref('')
const categoryId = ref('')
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const deptIds = ref<string[]>([])
const uploadError = ref('')

watch(open, (value) => {
  if (value) {
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
  if (!uploaded.value?.id) {
    uploadError.value = '请先选择并上传文件'
    return
  }
  emit('submit', {
    fileId: uploaded.value.id,
    filename: uploaded.value.originalName || files.value[0]?.name || 'file',
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
    description="支持图片、PPT、Excel、PSD、视频、音频等常见格式"
    :confirm-button-loading="saving"
    :confirm-button-disabled="!uploaded"
    @confirm="onConfirm"
  >
    <div class="flex flex-col gap-3">
      <FaFileUpload
        v-model="files"
        :max="1"
        :http-request="httpRequest"
        description="拖放或点击选择文件"
        @on-success="onSuccess"
      />
      <span v-if="uploadError" class="text-xs text-destructive">{{ uploadError }}</span>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">名称</span>
        <FaInput v-model="name" placeholder="留空则使用文件名" maxlength="120" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">分类</span>
        <CategoryPicker v-model="categoryId" :categories="props.categories" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">标签（最多 8 个）</span>
        <TagPicker v-model="tags" :tags="props.tags" />
      </label>
      <VisibilityDeptField v-model:visibility="visibility" v-model:dept-ids="deptIds" :dept-options="props.deptOptions" />
    </div>
  </FaModal>
</template>
