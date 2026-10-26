<script setup lang="ts">
import { ref, watch } from 'vue'
import { YdModal, YdFilePicker, YdButton, YdInput, YdLabel, YdAlert, useYdToast } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ModelRow } from '../types'
import { userApi } from '../api/user'
const props = defineProps<{ sdk: YuDreamPluginSdk; existing?: ModelRow }>()
const open = defineModel<boolean>({ default: false })
const emit = defineEmits<{ uploaded: [row: ModelRow] }>()
const name = ref(''), file = ref<File>(), busy = ref(false), error = ref('')
const toast = useYdToast()
watch(open, value => { if (value) { name.value = props.existing?.name ?? ''; file.value = undefined; error.value = '' } })
function selected(files: File[]) { file.value = files[0]; if (!name.value && file.value) name.value = file.value.name.replace(/\.zip$/i, '') }
function base64(file: File) { return new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(new Error('文件读取失败')); reader.readAsDataURL(file) }) }
async function upload() {
  if (busy.value) return
  error.value = ''
  if (!file.value || !name.value.trim()) { error.value = '请选择模型 ZIP 并填写名称'; return }
  if (file.value.size > 16 * 1024 * 1024) { error.value = '模型 ZIP 不能超过 16 MiB'; return }
  busy.value = true
  try {
    const row = await userApi(props.sdk).upload({ name: name.value.trim(), base64: await base64(file.value) }, props.existing?.id)
    open.value = false
    toast.success('模型已转换，可以预览和装备')
    emit('uploaded', row)
  }
  catch (e) { error.value = e instanceof Error ? e.message : '上传失败' }
  finally { busy.value = false }
}
</script>
<template>
  <YdModal v-model="open" :title="existing ? '上传新版本' : '上传模型'" :confirm-button-loading="busy" :close-on-click-overlay="!busy" :show-cancel-button="!busy" @confirm="upload">
    <div class="ysm-form">
      <YdAlert v-if="error" type="error">{{ error }}</YdAlert>
      <YdLabel>模型名称<YdInput v-model="name" :maxlength="100" :disabled="busy" /></YdLabel>
      <YdFilePicker accept=".zip,application/zip" label="选择模型 ZIP" @select="selected">
        <template #default="{ open: pick }"><YdButton variant="outline" :disabled="busy" @click="pick">{{ file?.name ?? '选择模型 ZIP' }}</YdButton></template>
      </YdFilePicker>
      <p>上传明文 YSM ZIP，包含主模型、PNG 贴图及数值动画，最大 16 MiB。转换通过后立即可用。</p>
    </div>
  </YdModal>
</template>
