<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetClickAction, PetClosetOption, PetCorner } from '../types'
import { FaButton, FaInput, FaPageHeader, FaPageMain, FaRadioGroup, FaSelect, FaSlider, FaSwitch } from '@yudream/components'
import { computed, ref } from 'vue'
import PetStage from '../components/PetStage.vue'
import SkinUploadModal from '../components/SkinUploadModal.vue'
import { usePetDefaults } from '../composables/usePetAdmin'
import { textureUrl } from '../composables/useMyPet'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = usePetDefaults(props.sdk)
const { loading, saving, form, closet, skinAvailable } = model

const uploadVisible = ref(false)

const modeOptions = [
  { label: '内置皮肤（Steve）', value: 'builtin' },
  { label: '按角色名解析', value: 'player' },
  { label: '上传皮肤', value: 'texture' },
]

const modelOptions = [
  { label: '经典（Steve）', value: 'classic' },
  { label: '纤细（Alex）', value: 'slim' },
]

const clickActionOptions: { label: string, value: PetClickAction }[] = [
  { label: '弹出菜单', value: 'menu' },
  { label: '打招呼动画', value: 'greet' },
  { label: '无响应', value: 'none' },
]

const cornerOptions: { label: string, value: PetCorner }[] = [
  { label: '右下角', value: 'bottom-right' },
  { label: '左下角', value: 'bottom-left' },
  { label: '右上角', value: 'top-right' },
  { label: '左上角', value: 'top-left' },
]

const closetOptions = computed(() => closet.value.map(item => ({
  label: item.itemName || item.id,
  value: item.textureHash,
})))

const sizeModel = computed<number[]>({
  get: () => [form.value.size],
  set: value => form.value.size = value?.[0] ?? 120,
})

const previewSkin = computed(() => {
  if (form.value.mode === 'builtin') {
    return props.sdk.assets.url('assets/steve.png')
  }
  if (form.value.mode === 'texture' && form.value.textureHash) {
    return textureUrl(form.value.textureHash)
  }
  return ''
})

async function onUploaded(item: PetClosetOption) {
  form.value.textureHash = item.textureHash
  await model.loadCloset()
}
</script>

<template>
  <section v-loading="loading" class="mc-pet-page">
    <FaPageHeader title="默认设置">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="mc-pet-mypet">
        <div class="mc-pet-mypet__form">
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">默认皮肤</span>
            <FaRadioGroup v-model="form.mode" :options="modeOptions" />
            <span class="mc-pet-field__hint">用户未自定义宠物时使用这里的默认配置。</span>
          </label>
          <label v-if="form.mode === 'player'" class="mc-pet-field">
            <span class="mc-pet-field__label">默认角色名</span>
            <FaInput v-model="form.playerName" placeholder="输入皮肤插件中的角色名" />
            <span class="mc-pet-field__hint">保存后按角色名实时解析皮肤；角色被删除或皮肤插件停用时自动回退内置皮肤。</span>
          </label>
          <div v-if="form.mode === 'texture'" class="mc-pet-field">
            <span class="mc-pet-field__label">默认皮肤文件</span>
            <div class="mc-pet-upload">
              <div class="mc-pet-upload__picker">
                <FaButton :disabled="!skinAvailable" @click="uploadVisible = true">
                  上传皮肤 PNG
                </FaButton>
                <FaSelect
                  v-if="closetOptions.length > 0"
                  v-model="form.textureHash"
                  :options="closetOptions"
                  placeholder="或从我上传过的皮肤中选择"
                />
              </div>
              <div class="mc-pet-upload__form">
                <FaRadioGroup v-model="form.model" :options="modelOptions" />
              </div>
            </div>
            <span v-if="!skinAvailable" class="mc-pet-field__hint">
              皮肤插件不可用或版本低于 1.3.0，暂时无法上传；可直接选择已有皮肤或改用其他默认来源。
            </span>
            <span v-else class="mc-pet-field__hint">
              上传会存入你（管理员）的皮肤衣柜并设为全局默认；所有未自定义的用户立即生效。
            </span>
          </div>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">点击行为</span>
            <FaRadioGroup v-model="form.clickAction" :options="clickActionOptions" />
          </label>
          <div class="mc-pet-field">
            <FaSwitch v-model="form.animation">
              待机动画
            </FaSwitch>
            <span class="mc-pet-field__hint">关闭后宠物保持静止站姿，降低低配设备的渲染开销。</span>
          </div>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">默认尺寸（{{ form.size }}px）</span>
            <FaSlider v-model="sizeModel" :min="48" :max="320" :step="4" :tooltip="false" />
          </label>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">默认停靠角</span>
            <FaSelect v-model="form.corner" :options="cornerOptions" />
          </label>
          <div class="mc-pet-actions">
            <FaButton type="submit" :loading="saving" @click="model.save">
              保存默认设置
            </FaButton>
          </div>
        </div>
        <aside class="mc-pet-mypet__preview">
          <h3>预览</h3>
          <PetStage v-if="previewSkin" :key="previewSkin" :skin="previewSkin" :slim="form.model === 'slim'" floating />
          <div v-else class="mc-pet-hint">
            按角色名解析的皮肤在保存后生效，此处仅预览内置皮肤效果。
          </div>
        </aside>
      </div>
      <SkinUploadModal :sdk="props.sdk" v-model:visible="uploadVisible" @uploaded="onUploaded" />
    </FaPageMain>
  </section>
</template>
