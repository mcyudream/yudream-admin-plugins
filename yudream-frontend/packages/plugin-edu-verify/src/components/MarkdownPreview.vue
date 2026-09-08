<script setup lang="ts">
import { MdPreview } from 'md-editor-v3'

withDefaults(defineProps<{
  content?: string | null
}>(), {
  content: '',
})
</script>

<template>
  <MdPreview
    class="ev-markdown-preview"
    language="zh-CN"
    preview-theme="github"
    code-theme="github"
    :model-value="content || ''"
  />
</template>
