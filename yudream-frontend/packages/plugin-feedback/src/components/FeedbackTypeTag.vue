<script setup lang="ts">
import type { FeedbackType } from '../types'
import { YdIcon, YdTag } from '@yudream/components'
import { typeIcon, typeLabel } from '../composables/utils'

/** 反馈类型标签：图标 + 文案，便于在列表里快速区分缺陷与建议。 */
const props = defineProps<{
  type: FeedbackType
  label?: string
}>()
</script>

<template>
  <YdTag variant="secondary" class="fb-type-tag">
    <YdIcon :name="typeIcon(props.type)" class="fb-type-tag__icon" />
    {{ props.label || typeLabel(props.type) }}
  </YdTag>
</template>

<style scoped>
.fb-type-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--fb-sp-2);
  white-space: nowrap;
}

.fb-type-tag__icon {
  font-size: 13px;
}
</style>
