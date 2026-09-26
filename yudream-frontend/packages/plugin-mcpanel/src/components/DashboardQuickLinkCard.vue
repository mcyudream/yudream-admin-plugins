<script setup lang="ts">
import { FaButton, FaIcon } from '@yudream/components'

interface DashboardCardLike {
  title?: string
  description?: string
  actionPath?: string
}

/** 首页快捷入口卡片体（应用市场 / 快速开始等）：只做引导跳转，不发起接口请求。 */
const props = defineProps<{
  card?: DashboardCardLike
  onOpen?: (card?: DashboardCardLike) => void
}>()

function openCard() {
  props.onOpen?.(props.card)
}
</script>

<template>
  <div class="mcp-dash-card mcp-dash-card--link">
    <p class="mcp-dash-card__desc">
      {{ card?.description || '前往 MC 面板查看详情' }}
    </p>
    <div v-if="card?.actionPath" class="mcp-dash-card__actions">
      <FaButton size="sm" @click="openCard">
        <FaIcon name="i-ri:arrow-right-line" />
        立即前往
      </FaButton>
    </div>
  </div>
</template>
