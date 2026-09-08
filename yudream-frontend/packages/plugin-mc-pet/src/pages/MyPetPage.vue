<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetClosetOption, PetCorner } from '../types'
import { FaButton, FaPageHeader, FaPageMain, FaRadioGroup, FaSelect, FaSlider, FaSwitch } from '@yudream/components'
import { computed, ref } from 'vue'
import PetStage from '../components/PetStage.vue'
import SkinUploadModal from '../components/SkinUploadModal.vue'
import { useMyPet } from '../composables/useMyPet'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = useMyPet(props.sdk)
const { loading, saving, pet, options, form, previewSkin, previewSlim, hasPosition } = model

const uploadVisible = ref(false)

const modeOptions = [
  { label: '跟随默认', value: 'default' },
  { label: '我的角色', value: 'player' },
  { label: '衣柜皮肤', value: 'closet' },
]

const cornerOptions: { label: string, value: PetCorner }[] = [
  { label: '右下角', value: 'bottom-right' },
  { label: '左下角', value: 'bottom-left' },
  { label: '右上角', value: 'top-right' },
  { label: '左上角', value: 'top-left' },
]

const playerOptions = computed(() => options.value.players.map(player => ({
  label: player.textureHash ? player.name : `${player.name}（无皮肤）`,
  value: player.name,
})))

const closetOptions = computed(() => options.value.closet.map(item => ({
  label: item.itemName,
  value: item.id,
})))

const sizeModel = computed<number[]>({
  get: () => [form.value.size],
  set: value => form.value.size = value?.[0] ?? 120,
})

const skinMissingHint = computed(() => {
  if (options.value.skinAvailable) {
    return ''
  }
  return '皮肤插件暂不可用，当前仅支持跟随默认（内置皮肤）。'
})

async function onUploaded(item: PetClosetOption) {
  await model.refreshOptions()
  form.value.mode = 'closet'
  form.value.closetItemId = item.id
}
</script>

<template>
  <section v-loading="loading" class="mc-pet-page">
    <FaPageHeader title="我的宠物">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="mc-pet-mypet">
        <div class="mc-pet-mypet__form">
          <div v-if="skinMissingHint" class="mc-pet-hint">
            {{ skinMissingHint }}
          </div>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">宠物来源</span>
            <FaRadioGroup v-model="form.mode" :options="modeOptions" />
            <span v-if="form.mode === 'default'" class="mc-pet-field__hint">
              使用管理员设置的默认宠物（当前：{{ pet?.source === 'texture' ? '皮肤材质' : '内置 Steve' }}）
            </span>
          </label>
          <label v-if="form.mode === 'player'" class="mc-pet-field">
            <span class="mc-pet-field__label">选择角色</span>
            <FaSelect
              v-model="form.playerName"
              :options="playerOptions"
              placeholder="请选择角色"
              :disabled="!playerOptions.length"
            />
            <span v-if="!playerOptions.length" class="mc-pet-field__hint">
              还没有角色，请先在皮肤插件中添加角色。
            </span>
          </label>
          <label v-if="form.mode === 'closet'" class="mc-pet-field">
            <span class="mc-pet-field__label">选择衣柜皮肤</span>
            <FaSelect
              v-model="form.closetItemId"
              :options="closetOptions"
              placeholder="请选择衣柜皮肤"
              :disabled="!closetOptions.length"
            />
            <div v-if="options.skinAvailable" class="mc-pet-actions">
              <FaButton variant="outline" @click="uploadVisible = true">
                上传皮肤
              </FaButton>
            </div>
            <span v-if="!closetOptions.length" class="mc-pet-field__hint">
              衣柜还是空的，可点击「上传皮肤」直接上传 PNG，或先在皮肤插件中收藏皮肤。
            </span>
          </label>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">宠物尺寸（{{ form.size }}px）</span>
            <FaSlider v-model="sizeModel" :min="48" :max="320" :step="4" :tooltip="false" />
          </label>
          <label class="mc-pet-field">
            <span class="mc-pet-field__label">默认停靠角</span>
            <FaSelect v-model="form.corner" :options="cornerOptions" />
            <span class="mc-pet-field__hint">未拖拽过宠物时停靠在该角落；拖拽后以拖拽位置为准。</span>
          </label>
          <div class="mc-pet-field">
            <FaSwitch v-model="form.hidden">
              隐藏宠物
            </FaSwitch>
            <span class="mc-pet-field__hint">隐藏后不再全站显示，可随时回来重新开启。</span>
          </div>
          <div class="mc-pet-actions">
            <FaButton type="submit" :loading="saving" @click="model.save">
              保存设置
            </FaButton>
            <FaButton v-if="hasPosition" variant="outline" :disabled="saving" @click="model.clearPosition">
              回到默认停靠角
            </FaButton>
          </div>
        </div>
        <aside class="mc-pet-mypet__preview">
          <h3>实时预览</h3>
          <PetStage :skin="previewSkin" :slim="previewSlim" floating />
        </aside>
      </div>
    </FaPageMain>
    <SkinUploadModal :sdk="props.sdk" v-model:visible="uploadVisible" @uploaded="onUploaded" />
  </section>
</template>
