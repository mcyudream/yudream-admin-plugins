<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { createCoverRenderer } from '../preview/cover-client'
import ModelCover from './ModelCover.vue'
import type { Bundle } from '../types'
const props = defineProps<{ bundle: Bundle; texture: string }>()
const renderer = createCoverRenderer()
onBeforeUnmount(renderer.dispose)
</script>
<template><ModelCover :name="bundle.name" :revision="texture" :load="() => renderer.render(props.bundle, props.texture)" /></template>
