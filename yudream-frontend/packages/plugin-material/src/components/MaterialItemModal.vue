<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaFileUpload, FaInput, FaModal, FaTextarea } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { uploadFileWithProgress } from '../api/upload'

/**
 * 子物料文件弹窗：mode=create 新增子物料（文件 + 名称），
 * mode=version 上传该子物料的新版本（文件 + 版本备注）。
 * 父物料与子物料的上传都先落宿主文件端点拿 fileId，再交给插件端点落库。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  mode: 'create' | 'version'
  /** mode=version 时的子物料名，仅用于文案 */
  itemName?: string
  saving?: boolean
}>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [{ fileId: string, filename: string, name: string, note: string }] }>()

const files = ref<FileItem[]>([])
const uploaded = ref<YuDreamPluginFileObject | null>(null)
const name = ref('')
const note = ref('')
const uploadError = ref('')

const isCreate = computed(() => props.mode === 'create')
const title = computed(() => isCreate.value ? '新增子物料' : `上传新版本：${props.itemName || '子物料'}`)
const description = computed(() => isCreate.value
  ? '子物料是父物料下的一个具名文件（如「原图」「设计稿」「成图」），拥有独立的版本链'
  : '新文件作为该子物料的下一版本，其余子物料不受影响')

watch(open, (value) => {
  if (value) {
    files.value = []
    uploaded.value = null
    name.value = ''
    note.value = ''
    uploadError.value = ''
  }
})

async function httpRequest(options: FileUploadRequestOptions) {
  return uploadFileWithProgress(props.sdk, options.file, options.onProgress)
}

function onSuccess(response: YuDreamPluginFileObject, file: File) {
  uploaded.value = response
  if (isCreate.value && !name.value.trim()) {
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
    note: note.value.trim(),
  })
}
</script>

<template>
  <FaModal
    v-model="open"
    :title="title"
    :description="description"
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
      <label v-if="isCreate" class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">子物料名称</span>
        <FaInput v-model="name" placeholder="如「原图」「设计稿」「成图」，留空则使用文件名" maxlength="60" />
      </label>
      <label v-else class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">版本备注</span>
        <FaTextarea v-model="note" placeholder="这次改了什么？（选填）" maxlength="200" :rows="3" />
      </label>
    </div>
  </FaModal>
</template>
