<script setup lang="ts">
// 活动默认封面：未上传封面时按活动标题稳定生成配色（固定色板取哈希），像素网格纹理 + 标题首字，
// 列表与详情不再出现空白图片框；纯 CSS，无额外图片资源。
import { YdIcon } from '@yudream/components'
import { computed } from 'vue'

defineOptions({ name: 'ActivityCoverPlaceholder' })

const props = defineProps<{ title?: string, size?: 'card' | 'hero' }>()

const PALETTE = [
  ['#3c8527', '#1f4d14'],
  ['#2f6fb5', '#173a63'],
  ['#b5652f', '#5e3115'],
  ['#7a3fb5', '#3d1f5e'],
  ['#2f9a8f', '#174d47'],
  ['#b53f5b', '#5e1f2e'],
] as const

const colors = computed(() => {
  let hash = 0
  for (const char of props.title || '') {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  }
  return PALETTE[hash % PALETTE.length]!
})
const initial = computed(() => [...(props.title || '活动').trim()][0] || '活')
</script>

<template>
  <div
    class="activity-cover-placeholder"
    :class="`activity-cover-placeholder--${size || 'card'}`"
    :style="{ '--cover-from': colors[0], '--cover-to': colors[1] }"
    role="img"
    :aria-label="`${title || '活动'}（默认封面）`"
  >
    <span class="activity-cover-placeholder__initial" aria-hidden="true">{{ initial }}</span>
    <YdIcon name="i-ri:flag-2-line" class="activity-cover-placeholder__mark" aria-hidden="true" />
  </div>
</template>

<style scoped>
.activity-cover-placeholder {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 120px;
  overflow: hidden;
  color: rgb(255 255 255 / 92%);
  background:
    linear-gradient(rgb(255 255 255 / 6%) 1px, transparent 1px) 0 0 / 16px 16px,
    linear-gradient(90deg, rgb(255 255 255 / 6%) 1px, transparent 1px) 0 0 / 16px 16px,
    linear-gradient(135deg, var(--cover-from), var(--cover-to));
}

.activity-cover-placeholder__initial {
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  text-shadow: 3px 3px 0 rgb(0 0 0 / 35%);
}

.activity-cover-placeholder--hero .activity-cover-placeholder__initial {
  font-size: 88px;
}

.activity-cover-placeholder__mark {
  position: absolute;
  bottom: 10px;
  left: 12px;
  font-size: 18px;
  opacity: 0.7;
}
</style>
