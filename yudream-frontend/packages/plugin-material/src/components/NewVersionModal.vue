<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaFileUpload, FaModal, FaTextarea } from '@yudream/components'
import { ref, watch } from 'vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, saving?: boolean }>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [{ fileId: string, filename: string, note: string }] }>()

const files = ref<FileItem[]>([])
const uploaded = ref<YuDreamPluginFileObject | null>(null)
const note = ref('')
const uploadError = ref('')

watch(open, (value) => {
  if (value) {
    files.value = []
    uploaded.value = null
    note.value = ''
    uploadError.value = ''
  }
})

async function httpRequest(options: FileUploadRequestOptions) {
  return props.sdk.files.uploadImage(options.file, { module: 'material', publicAccess: false })
}

function onSuccess(response: YuDreamPluginFileObject) {
  uploaded.value = response
}

function onConfirm() {
  if (!uploaded.value?.id) {
    uploadError.value = '请先选择并上传文件'
    return
  }
  emit('submit', {
    fileId: uploaded.value.id,
    filename: uploaded.value.originalName || files.value[0]?.name || 'file',
    note: note.value.trim(),
  })
}
</script>

<template>
  <FaModal
    v-model="open"
    title="上传新版本"
    description="新文件会作为下一个版本，旧版本保留可随时回滚"
    :confirm-button-loading="saving"
    :confirm-button-disabled="!uploaded"
    @confirm="onConfirm"
  >
    <div class="flex flex-col gap-3">
      <FaFileUpload
        v-model="files"
        :max="1"
        :http-request="httpRequest"
        description="拖放或点击选择新文件"
        @on-success="onSuccess"
      />
      <span v-if="uploadError" class="text-xs text-destructive">{{ uploadError }}</span>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">版本备注</span>
        <FaTextarea v-model="note" placeholder="这次改了什么？（选填）" maxlength="200" :rows="3" />
      </label>
    </div>
  </FaModal>
</template>
