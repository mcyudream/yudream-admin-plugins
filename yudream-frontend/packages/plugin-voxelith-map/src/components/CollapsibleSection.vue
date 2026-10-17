<script setup lang="ts">
import { YdAction, YdIcon } from '@yudream/components'
import { ref } from 'vue'

/**
 * 轻量折叠区块：标题行整行可点，正文 v-if 卸载式折叠（不渲染就不占 DOM）。
 * 详情页的「子设置」都用它收敛，默认收起，避免一屏铺满十几个表单项。
 *
 * `storageKey`：可选。给出时把展开状态记忆到 localStorage（按 key 隔离），
 * 页面刷新或切换地图后恢复用户上次的展开偏好；不给则每次按 defaultOpen 初始化。
 */
const props = withDefaults(defineProps<{
  title: string
  /** 标题右侧的灰字说明（如当前绑定模组数） */
  subtitle?: string
  defaultOpen?: boolean
  /** 展开状态记忆键（localStorage，前缀 voxelith-collapse:） */
  storageKey?: string
}>(), { defaultOpen: false })

const STORAGE_PREFIX = 'voxelith-collapse:'

const open = ref(props.defaultOpen)
if (props.storageKey) {
  try {
    const saved = localStorage.getItem(STORAGE_PREFIX + props.storageKey)
    if (saved !== null) {
      open.value = saved === '1'
    }
  }
  catch {
    // 隐私模式等 localStorage 不可用：退回默认值即可
  }
}

function toggle(): void {
  open.value = !open.value
  if (props.storageKey) {
    try {
      localStorage.setItem(STORAGE_PREFIX + props.storageKey, open.value ? '1' : '0')
    }
    catch {
      // 存不下就只当次会话生效
    }
  }
}
</script>

<template>
  <div class="voxelith-collapse">
    <YdAction
      type="button"
      class="voxelith-collapse-head"
      :aria-expanded="open"
      @click="toggle"
    >
      <YdIcon :name="open ? 'i-mdi:chevron-down' : 'i-mdi:chevron-right'" class="shrink-0" />
      <span class="voxelith-collapse-title">{{ title }}</span>
      <span v-if="subtitle" class="voxelith-collapse-subtitle">{{ subtitle }}</span>
    </YdAction>
    <Transition name="voxelith-collapse">
      <div v-if="open" class="voxelith-collapse-body">
        <slot />
      </div>
    </Transition>
  </div>
</template>
