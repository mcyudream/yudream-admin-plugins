<script setup lang="ts">
import { ref, onBeforeUnmount, watch, nextTick } from 'vue'
import { YdSelect, YdButton, YdAlert, YdLoadingOverlay } from '@yudream/components'
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
  animation.value = bundle.animations.idle ? 'idle' : Object.keys(bundle.animations)[0] ?? ''
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
    <YdAlert v-if="error" type="error">{{ error }}</YdAlert>
    <YdLoadingOverlay :loading="loading"><div ref="container" class="ysm-viewport" /></YdLoadingOverlay>
    <div class="ysm-actions">
      <YdSelect v-model="texture" aria-label="模型贴图" :options="bundle.textures.map(t => ({ label: t.name, value: t.name }))" />
      <YdSelect v-if="Object.keys(bundle.animations).length" v-model="animation" aria-label="预览动作" :options="Object.keys(bundle.animations).map(a => ({ label: a, value: a }))" />
      <YdButton variant="outline" @click="playing = !playing">{{ playing ? '暂停' : '播放' }}</YdButton>
      <YdButton variant="outline" @click="handle?.resetCamera()">复位视角</YdButton>
    </div>
  </div>
</template>
