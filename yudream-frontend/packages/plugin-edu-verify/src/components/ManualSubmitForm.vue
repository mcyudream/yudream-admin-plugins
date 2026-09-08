<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ManualMaterialPayload } from '../types'
import { FaButton, FaFileUpload, FaIcon, FaInput, FaLabel, FaSelect, FaTextarea } from '@yudream/components'
import { ref, watch } from 'vue'
import { assertUploadable, uploadFileWithProgress } from '../api/upload'
import { MATERIAL_KIND_OPTIONS } from '../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  showEmail?: boolean
  submitting?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  submit: [payload: {
    email: string
    realName: string
    schoolName: string
    note: string
    vcode: string
    materials: ManualMaterialPayload[]
  }]
}>()

const email = defineModel<string>('email', { default: '' })
const vcode = defineModel<string>('vcode', { default: '' })
const realName = defineModel<string>('realName', { default: '' })
const schoolName = defineModel<string>('schoolName', { default: '' })
const note = ref('')
const kind = ref('CHSI_REPORT')
const files = ref<FileItem[]>([])
const materials = ref<ManualMaterialPayload[]>([])
const uploadError = ref('')
const formError = ref('')

watch(files, (list) => {
  const names = new Set(list.map(item => item.name).filter(Boolean))
  if (list.length === 0) {
    materials.value = []
    return
  }
  materials.value = materials.value.filter(item => names.has(item.filename))
}, { deep: true })

function beforeUpload(file: File) {
  uploadError.value = ''
  try {
    if (props.showEmail && !email.value.trim()) {
      throw new Error('请先填写联系邮箱再上传材料')
    }
    assertUploadable(file)
    return true
  }
  catch (cause) {
    uploadError.value = cause instanceof Error ? cause.message : '文件不符合要求'
    return false
  }
}

async function httpRequest(options: FileUploadRequestOptions) {
  uploadError.value = ''
  return uploadFileWithProgress(props.sdk, options.file, email.value, options.onProgress)
}

function onSuccess(response: YuDreamPluginFileObject, file: File) {
  if (!response.id) {
    uploadError.value = '上传成功但未返回文件 ID，请重新上传'
    return
  }
  const filename = response.originalName || file.name || 'file'
  const payload: ManualMaterialPayload = {
    fileId: response.id,
    filename,
    contentType: file.type || 'application/octet-stream',
    kind: kind.value,
  }
  materials.value = [
    ...materials.value.filter(item => item.fileId !== payload.fileId && item.filename !== filename),
    payload,
  ]
}

function reset() {
  note.value = ''
  kind.value = 'CHSI_REPORT'
  files.value = []
  materials.value = []
  uploadError.value = ''
  formError.value = ''
}

function onSubmit() {
  formError.value = ''
  if (props.showEmail && !email.value.trim()) {
    formError.value = '请填写联系邮箱'
    return
  }
  if (!realName.value.trim()) {
    formError.value = '请填写真实姓名'
    return
  }
  if (!schoolName.value.trim()) {
    formError.value = '请填写学校名称'
    return
  }
  if (materials.value.length === 0) {
    formError.value = '请至少上传一份证明材料'
    return
  }
  emit('submit', {
    email: email.value.trim(),
    realName: realName.value.trim(),
    schoolName: schoolName.value.trim(),
    note: note.value.trim(),
    vcode: vcode.value.trim(),
    materials: materials.value,
  })
}

defineExpose({ reset })
</script>

<template>
  <form class="ev-form" @submit.prevent="onSubmit">
    <FaLabel v-if="showEmail" label="联系邮箱" class="ev-field">
      <FaInput v-model="email" class="w-full" maxlength="120" placeholder="用于绑定认证记录并完成注册" :disabled="disabled" />
      <span class="ev-field-hint">不必是教育邮箱。审核通过后须用同一邮箱注册。</span>
    </FaLabel>
    <FaLabel label="真实姓名" class="ev-field">
      <FaInput v-model="realName" class="w-full" maxlength="40" placeholder="与证件一致" :disabled="disabled" />
    </FaLabel>
    <FaLabel label="学校名称" class="ev-field">
      <FaInput v-model="schoolName" class="w-full" maxlength="80" placeholder="如：某某大学" :disabled="disabled" />
    </FaLabel>
    <FaLabel label="学信网在线验证码（选填）" class="ev-field">
      <FaInput v-model="vcode" class="w-full" maxlength="16" placeholder="16 位字母或数字，有则填写" :disabled="disabled" />
    </FaLabel>
    <FaLabel label="补充说明" class="ev-field">
      <FaTextarea v-model="note" :rows="3" maxlength="300" placeholder="可选，说明材料与学籍关系" :disabled="disabled" />
    </FaLabel>
    <FaLabel label="材料类型" class="ev-field">
      <FaSelect v-model="kind" :options="MATERIAL_KIND_OPTIONS" :disabled="disabled" />
    </FaLabel>
    <FaLabel label="证明材料" class="ev-field">
      <FaFileUpload
        v-model="files"
        :max="6"
        :http-request="httpRequest"
        :before-upload="beforeUpload"
        :disabled="disabled"
        description="最多 6 份，单份不超过 8MB，仅 JPEG/PNG/GIF/WebP 或 PDF"
        @on-success="onSuccess"
      />
      <span class="ev-field-hint">材料类型按上传时的选择记录。建议上传学信网报告截图、学生证或录取通知书。请先填写联系邮箱再上传；文件会校验真实类型并限流，过期未提交将自动清理。</span>
      <span v-if="uploadError" class="ev-field-hint">{{ uploadError }}</span>
    </FaLabel>
    <p v-if="formError" class="ev-field-hint">{{ formError }}</p>
    <div class="ev-actions ev-actions-end">
      <FaButton type="submit" :loading="submitting" :disabled="disabled">
        <FaIcon name="i-ri:send-plane-line" />
        提交人工审核
      </FaButton>
    </div>
  </form>
</template>
