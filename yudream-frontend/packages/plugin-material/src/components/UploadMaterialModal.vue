<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CategoryView } from '../types'
import { FaFileUpload, FaInput, FaModal, FaSelect } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { uploadFileWithProgress } from '../api/upload'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  categories: CategoryView[]
  saving?: boolean
}>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [{ fileId: string, filename: string, name: string, categoryId: string, tags: string[] }] }>()

const files = ref<FileItem[]>([])
const uploaded = ref<YuDreamPluginFileObject | null>(null)
const name = ref('')
const categoryId = ref('')
const tags = ref('')
const uploadError = ref('')

const categoryOptions = computed(() => [
  { label: '未分类', value: '' },
  ...props.categories.map(category => ({ label: category.name, value: category.id })),
])

watch(open, (value) => {
  if (value) {
    files.value = []
    uploaded.value = null
    name.value = ''
    categoryId.value = ''
    tags.value = ''
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
  const tagList = tags.value.split(/[,，]/).map(tag => tag.trim()).filter(Boolean)
  emit('submit', {
    fileId: uploaded.value.id,
    filename: uploaded.value.originalName || files.value[0]?.name || 'file',
    name: name.value.trim(),
    categoryId: categoryId.value,
    tags: tagList,
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
        <FaSelect v-model="categoryId" :options="categoryOptions" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">标签（逗号分隔，最多 8 个）</span>
        <FaInput v-model="tags" placeholder="如：周年庆, 海报" />
      </label>
    </div>
  </FaModal>
</template>
