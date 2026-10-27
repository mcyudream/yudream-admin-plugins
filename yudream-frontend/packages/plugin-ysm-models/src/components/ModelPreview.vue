<script setup lang="ts">
import { ref, onBeforeUnmount, watch, nextTick } from 'vue'
import { YdSelect, YdButton, YdTag, YdLoadingOverlay, YdAlert } from '@yudream/components'
import ModelPoster from './ModelPoster.vue'
import { previewAnimation } from '../preview/animation'
import type { Bundle } from '../types'
import { mountPreview, type PreviewHandle } from '../preview/renderer'

const props = defineProps<{ bundle: Bundle }>()
const texture = defineModel<string>('texture', { default: '' })
const container = ref<HTMLElement>()
const error = ref(''), loading = ref(false), animation = ref('idle'), playing = ref(true)
let handle: PreviewHandle | undefined
let generation = 0
watch(() => props.bundle, async bundle => {
  const current = ++generation
  handle?.dispose()
  handle = undefined
  error.value = ''
  loading.value = true
  texture.value = bundle.textures[0].name
  animation.value = previewAnimation(bundle)
  playing.value = true
  await nextTick()
  try {
    const next = await mountPreview(container.value!, bundle)
    if (current !== generation) { next.dispose(); return }
    handle = next
    handle.texture(texture.value)
    handle.animation(animation.value)
    handle.playing(playing.value)
  }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '预览加载失败' }
  finally { if (current === generation) loading.value = false }
}, { immediate: true })
watch(texture, name => handle?.texture(name))
watch(animation, name => handle?.animation(name))
watch(playing, value => handle?.playing(value))
onBeforeUnmount(() => { generation++; handle?.dispose() })
</script>

<template>
  <div class="ysm-preview">
    <div v-if="error" class="ysm-preview-note"><YdTag variant="secondary">二维预览</YdTag><span>当前环境无法使用三维预览，已显示正、背面静态预览，仍可选择贴图和装备。</span></div>
    <ModelPoster v-if="error" :bundle="bundle" :texture="texture" />
    <YdLoadingOverlay v-show="!error" :loading="loading"><div ref="container" class="ysm-viewport" /></YdLoadingOverlay>
    <YdAlert v-if="bundle.compatibility?.length" type="warning"><ul><li v-for="note in bundle.compatibility" :key="note">{{ note }}</li></ul></YdAlert>
    <div class="ysm-actions">
      <YdSelect v-model="texture" aria-label="模型贴图" :options="bundle.textures.map(t => ({ label: t.name, value: t.name }))" />
      <YdSelect v-if="!error && Object.keys(bundle.animations).length" v-model="animation" aria-label="预览动作" :options="Object.keys(bundle.animations).map(a => ({ label: a, value: a }))" />
      <YdButton v-if="!error" variant="outline" @click="playing = !playing">{{ playing ? '暂停' : '播放' }}</YdButton>
      <YdButton v-if="!error" variant="outline" @click="handle?.resetCamera()">复位视角</YdButton>
    </div>
  </div>
</template>
