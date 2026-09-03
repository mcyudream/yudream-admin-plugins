<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { MdPreview } from 'md-editor-v3'
import { computed } from 'vue'
import { resolveMarkdownFileUrls } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  content?: string
}>()

const resolvedContent = computed(() => resolveMarkdownFileUrls(props.sdk, props.content))
</script>

<template>
  <MdPreview
    class="ap-markdown-preview"
    language="zh-CN"
    preview-theme="github"
    code-theme="github"
    :model-value="resolvedContent"
  />
</template>
