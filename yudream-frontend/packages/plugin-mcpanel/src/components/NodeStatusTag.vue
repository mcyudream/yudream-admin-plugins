<script setup lang="ts">
import type { McpNodeStatus } from '../types'
import { computed } from 'vue'
import { FaTag } from '@yudream/components'

const props = defineProps<{ status?: string | null }>()

const STATUS_META: Record<string, { label: string, variant: 'default' | 'secondary' | 'outline' | 'destructive' }> = {
  online: { label: '在线', variant: 'default' },
  connecting: { label: '连接中', variant: 'secondary' },
  enrolling: { label: '注册中', variant: 'secondary' },
  offline: { label: '离线', variant: 'outline' },
}

const meta = computed(() => {
  const status = (props.status ?? '') as McpNodeStatus | ''
  return STATUS_META[status] ?? { label: props.status || '未知', variant: 'outline' as const }
})
</script>

<template>
  <FaTag :variant="meta.variant">
    {{ meta.label }}
  </FaTag>
</template>
