<template>
  <FaModal v-model="open" title="上传皮肤" :confirm-button-loading="saving" :before-close="handleBeforeClose">
    <div class="mc-pet-upload">
      <div class="mc-pet-upload__picker">
        <PetStage :skin="previewSkin" :slim="form.model === 'slim'" :width="120" :height="144" />
        <FaButton variant="outline" :disabled="saving" @click="pickFile">
          {{ form.base64 ? '重新选择' : '选择皮肤 PNG' }}
        </FaButton>
        <span class="mc-pet-muted">64x64 或 64x32 PNG，约 1MB 以内</span>
      </div>
      <div class="mc-pet-upload__form">
        <FaLabel label="皮肤名称" class="mc-pet-field">
          <FaInput v-model="form.name" maxlength="40" placeholder="默认为文件名" />
        </FaLabel>
        <FaLabel label="模型" class="mc-pet-field">
          <FaRadioGroup v-model="form.model" :options="modelOptions" />
        </FaLabel>
        <span class="mc-pet-muted">上传后保存到你的皮肤衣柜，并可直接选为宠物皮肤。</span>
      </div>
    </div>
  </FaModal>
</template>

<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetClosetOption } from '../types'
import { FaButton, FaInput, FaLabel, FaModal, FaRadioGroup, useFaToast } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcPetApi } from '../api/mc-pet-api'
import PetStage from './PetStage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'uploaded': [item: PetClosetOption]
}>()

const MAX_FILE_SIZE = 1024 * 1024

const api = createMcPetApi(props.sdk)
const toast = useFaToast()

const saving = ref(false)
const form = reactive({
  name: '',
  model: 'classic' as 'classic' | 'slim',
  base64: '',
  fileName: '',
})

const modelOptions = [
  { label: '经典（Steve）', value: 'classic' },
  { label: '纤细（Alex）', value: 'slim' },
]

const open = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

const previewSkin = computed(() => form.base64 || props.sdk.assets.url('assets/steve.png'))

watch(open, (value) => {
  if (value) {
    form.name = ''
    form.model = 'classic'
    form.base64 = ''
    form.fileName = ''
  }
})

/** CI 规范禁止模板内原生 input，文件选择用 JS 动态创建。 */
function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/png,.png'
  input.onchange = () => {
    const file = input.files?.[0]
    if (file) {
      void readFile(file)
    }
  }
  input.click()
}

async function readFile(file: File) {
  if (file.size > MAX_FILE_SIZE) {
    toast.warning('皮肤文件过大（上限 1MB）')
    return
  }
  try {
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result || ''))
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(file)
    })
    form.base64 = dataUrl
    form.fileName = file.name
    if (!form.name.trim()) {
      form.name = file.name.replace(/\.png$/i, '')
    }
  }
  catch {
    toast.warning('读取文件失败，请重试')
  }
}

async function handleBeforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    if (!saving.value) {
      done()
    }
    return
  }
  if (!form.base64) {
    toast.warning('请先选择皮肤 PNG 文件')
    return
  }
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    const item = await api.me.uploadSkin({
      name: form.name.trim() || undefined,
      model: form.model,
      base64: form.base64,
    })
    toast.success('皮肤已上传到衣柜')
    done()
    emit('uploaded', item)
  }
  catch (error) {
    toast.warning(error instanceof Error && error.message ? error.message : '上传失败')
  }
  finally {
    saving.value = false
  }
}
</script>
