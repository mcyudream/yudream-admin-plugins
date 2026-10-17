<script setup lang="ts">
import type { FeedbackSeverity } from '../types'
import { severityMeta } from '../composables/utils'

/** 严重程度标签：文字 + 语义色圆点，致命/严重用暖色，与状态标签的色阶不冲突。 */
const props = defineProps<{
  severity: FeedbackSeverity
  label?: string
}>()
</script>

<template>
  <span class="fb-severity" :style="{ color: severityMeta(props.severity).tone }">
    <span class="fb-severity__dot" :style="{ background: severityMeta(props.severity).tone }" aria-hidden="true" />
    {{ props.label || severityMeta(props.severity).label }}
  </span>
</template>

<style scoped>
.fb-severity {
  display: inline-flex;
  align-items: center;
  gap: var(--fb-sp-2);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.fb-severity__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--fb-radius-pill);
}
</style>
