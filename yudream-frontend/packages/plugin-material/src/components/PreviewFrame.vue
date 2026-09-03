<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PreviewInfo } from '../types'
import { FaIcon } from '@yudream/components'
import { computed } from 'vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  info?: PreviewInfo | null
  loading?: boolean
  materialType?: string
  ext?: string
}>()

/** DIRECT 模式给的是平台签名公开路径（/api/**），用 assetUrl 解析成完整地址；KKFILE 已是绝对地址。 */
const resolvedUrl = computed(() => {
  if (!props.info?.url) {
    return ''
  }
  return props.info.mode === 'DIRECT' ? props.sdk.files.assetUrl(props.info.url) : props.info.url
})

const renderAs = computed<'iframe' | 'image' | 'video' | 'audio' | 'none'>(() => {
  if (!props.info || props.info.mode === 'NONE' || !props.info.url) {
    return 'none'
  }
  if (props.info.mode === 'KKFILE') {
    return 'iframe'
  }
  if (props.materialType === 'IMAGE') {
    return 'image'
  }
  if (props.materialType === 'VIDEO') {
    return 'video'
  }
  if (props.materialType === 'AUDIO') {
    return 'audio'
  }
  // pdf / 文本类直接用 iframe 让浏览器原生渲染
  return 'iframe'
})
</script>

<template>
  <div class="material-preview">
    <div v-if="loading" class="material-preview-empty">
      <FaIcon name="i-ri:loader-4-line" class="animate-spin text-2xl" />
      <span>正在加载预览…</span>
    </div>
    <template v-else-if="info && renderAs !== 'none'">
      <iframe
        v-if="renderAs === 'iframe'"
        :src="resolvedUrl"
        class="material-preview-frame"
        allowfullscreen
      />
      <img v-else-if="renderAs === 'image'" :src="resolvedUrl" :alt="ext" class="material-preview-image">
      <video v-else-if="renderAs === 'video'" :src="resolvedUrl" class="material-preview-frame" controls />
      <div v-else-if="renderAs === 'audio'" class="material-preview-empty">
        <FaIcon name="i-ri:music-2-line" class="text-4xl text-secondary-foreground/60" />
        <audio :src="resolvedUrl" controls class="w-full max-w-md" />
      </div>
    </template>
    <div v-else class="material-preview-empty">
      <FaIcon name="i-ri:file-damage-line" class="text-4xl text-secondary-foreground/60" />
      <span>{{ info?.message || '暂不支持在线预览，请下载后查看' }}</span>
    </div>
  </div>
</template>
