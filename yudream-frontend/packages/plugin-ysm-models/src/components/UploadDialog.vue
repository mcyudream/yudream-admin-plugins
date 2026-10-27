<script setup lang="ts">
import { ref, watch } from 'vue'
import { YdModal, YdFilePicker, YdButton, YdInput, YdLabel, YdAlert, useYdToast } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ModelRow, ModelUploadPolicy } from '../types'
import { userApi } from '../api/user'
const props = defineProps<{ sdk: YuDreamPluginSdk; existing?: ModelRow; policy?: ModelUploadPolicy }>()
const open = defineModel<boolean>({ default: false })
const emit = defineEmits<{ uploaded: [row: ModelRow] }>()
const name = ref(''), file = ref<File>(), busy = ref(false), error = ref('')
const toast = useYdToast()
watch(open, value => { if (value) { name.value = props.existing?.name ?? ''; file.value = undefined; error.value = '' } })
function selected(files: File[]) { file.value = files[0]; if (!name.value && file.value) name.value = file.value.name.replace(/\.(zip|ysm)$/i, '') }
function base64(file: File) { return new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(new Error('文件读取失败')); reader.readAsDataURL(file) }) }
async function beforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (busy.value) return
  if (action === 'confirm') await upload()
  else done()
}
async function upload() {
  if (busy.value) return
  error.value = ''
  if (!props.policy?.canUpload) { error.value = '当前账号暂不能上传，请刷新页面确认上传权限'; return }
  if (!file.value || !name.value.trim()) { error.value = '请选择 .ysm 或 ZIP 模型 并填写名称'; return }
  if (file.value.size > 16 * 1024 * 1024) { error.value = '模型文件不能超过 16 MiB'; return }
  busy.value = true
  try {
    const row = await userApi(props.sdk).upload({ name: name.value.trim(), base64: await base64(file.value) }, props.existing?.id)
    open.value = false
    toast.success(row.reviewStatus === 'PENDING' ? '模型已提交，等待管理员审核' : '模型已转换，可以预览和装备')
    emit('uploaded', row)
  }
  catch (e) { error.value = e instanceof Error ? e.message : '上传失败' }
  finally { busy.value = false }
}
</script>
<template>
  <YdModal v-model="open" :title="existing ? '上传新版本' : '上传模型'" :confirm-button-loading="busy" :close-on-click-overlay="!busy" :show-cancel-button="!busy" :before-close="beforeClose">
    <div class="ysm-form">
      <YdAlert v-if="error" type="error">{{ error }}</YdAlert>
      <YdAlert v-if="policy?.reviewRequired">{{ existing ? '新版本需要重新审核。提交后，正在使用此模型的角色将暂时恢复皮肤外观；审核通过后恢复模型。' : '上传完成后可以预览，审核通过后才能装备。' }}</YdAlert>
      <YdLabel>模型名称<YdInput v-model="name" :maxlength="100" :disabled="busy" /></YdLabel>
      <YdFilePicker accept=".ysm,.zip,application/zip,application/octet-stream" label="选择 .ysm 或 ZIP 模型" @select="selected">
        <template #default="{ open: pick }"><YdButton variant="outline" :disabled="busy" @click="pick">{{ file?.name ?? '选择 .ysm 或 ZIP 模型' }}</YdButton></template>
      </YdFilePicker>
      <p>支持原始 .ysm 和明文 ZIP，最大 16 MiB。模型会转换为通用格式；YSM 专用控制器、物理和外观菜单的兼容情况将在预览中显示。</p>
    </div>
  </YdModal>
</template>
