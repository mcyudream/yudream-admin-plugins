<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { YdButton, YdSkeleton } from '@yudream/components'
const props = defineProps<{ name: string; revision: string; load: () => Promise<string>; compact?: boolean; passive?: boolean }>()
const host = ref<HTMLElement>(), source = ref(''), failed = ref(false), visible = ref(false)
let observer: IntersectionObserver | undefined, generation = 0
async function draw() {
  const current = ++generation
  source.value = ''; failed.value = false
  if (!visible.value) return
  try { const result = await props.load(); if (generation === current) source.value = result }
  catch { if (generation === current) failed.value = true }
}
onMounted(() => {
  if (!('IntersectionObserver' in window)) { visible.value = true; void draw(); return }
  observer = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) { visible.value = true; observer?.disconnect(); void draw() }
  }, { rootMargin: '120px' })
  observer.observe(host.value!)
})
watch(() => props.revision, draw)
onBeforeUnmount(() => { generation++; observer?.disconnect() })
</script>
<template>
  <span ref="host" class="ysm-cover" :class="{ 'ysm-cover--compact': compact }">
    <img v-if="source" :src="source" :alt="`${name}，正面与背面二维封面`" width="480" height="360" draggable="false" />
    <span v-else-if="failed" class="ysm-cover-error"><span>封面加载失败</span><YdButton v-if="!compact && !passive" size="sm" variant="ghost" @click.stop="draw">重试</YdButton></span>
    <YdSkeleton v-else variant="rect" width="100%" height="100%" />
    <span v-if="source && !compact" class="ysm-cover-labels" aria-hidden="true"><span>正面</span><span>背面</span></span>
  </span>
</template>
