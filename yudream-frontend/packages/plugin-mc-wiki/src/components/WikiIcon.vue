<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ src?: string | null, label?: string, size?: number }>(), { size: 32 })
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
const showImage = computed(() => !!props.src && !failed.value)
const fallbackText = computed(() => (props.label ?? '?').trim().charAt(0) || '?')
</script>

<template>
  <span class="mc-wiki-icon" :style="{ width: `${size}px`, height: `${size}px` }">
    <img v-if="showImage" :src="src!" :alt="label ?? ''" loading="lazy" @error="failed = true">
    <span v-else class="mc-wiki-icon-fallback" :style="{ fontSize: `${Math.max(12, size / 2.4)}px` }">{{ fallbackText }}</span>
  </span>
</template>
