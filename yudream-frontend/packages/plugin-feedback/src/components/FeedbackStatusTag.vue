<script setup lang="ts">
import type { FeedbackStatus } from '../types'
import { YdTag } from '@yudream/components'
import { statusMeta } from '../composables/utils'

/** 受理状态标签：颜色与后端给出的状态标签保持一致，避免各处自行拼文案。 */
const props = defineProps<{
  status: FeedbackStatus
  label?: string
}>()
</script>

<template>
  <YdTag :variant="statusMeta(props.status).variant" class="fb-status-tag">
    <span class="fb-status-tag__dot" :style="{ background: statusMeta(props.status).tone }" aria-hidden="true" />
    {{ props.label || statusMeta(props.status).label }}
  </YdTag>
</template>

<style scoped>
.fb-status-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--fb-sp-2);
  white-space: nowrap;
}

.fb-status-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--fb-radius-pill);
}
</style>
