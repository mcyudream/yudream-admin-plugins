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

<style scoped>
/* 预览不自带底色与文字色：跟随所在页面（后台明暗、公开站主题皮肤），避免深色页面里出现白底块 */
.ap-markdown-preview {
  --md-bk-color: transparent;
  --md-color: inherit;
  --md-theme-color: inherit;
  --md-theme-color-reverse: inherit;
  color: inherit;
  background: transparent;
}

.ap-markdown-preview :deep(.md-editor-preview) {
  color: inherit;
}

.ap-markdown-preview :deep(.md-editor-preview a) {
  color: var(--yb-site-primary, var(--color-link-6, currentColor));
}

.ap-markdown-preview :deep(.md-editor-preview code:not(pre code)) {
  color: inherit;
  background: rgb(127 127 127 / 18%);
}

/* github 预览主题把表格行写死白底（tr background:#fff），深色公开页上与继承的白字叠成白底白字；
   改为透明行底 + 半透明描边，表头垫一层灰底，明暗页面都可读。 */
.ap-markdown-preview :deep(.github-theme table tr) {
  background-color: transparent;
  border-top: 1px solid rgb(127 127 127 / 35%);
}

.ap-markdown-preview :deep(.github-theme table th),
.ap-markdown-preview :deep(.github-theme table td) {
  border: 1px solid rgb(127 127 127 / 35%);
}

.ap-markdown-preview :deep(.github-theme table thead tr) {
  background-color: rgb(127 127 127 / 18%);
}

/* md-editor 给缩放图片（.medium-zoom-image）写死白底，透明 GIF/PNG 会在深色页面上垫成白块 */
.ap-markdown-preview :deep(.md-editor-preview img.medium-zoom-image) {
  background-color: transparent;
}
</style>
