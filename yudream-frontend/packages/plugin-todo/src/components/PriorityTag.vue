<script setup lang="ts">
import type { TodoPriority } from '../types'
import { YdTag } from '@yudream/components'
import { computed } from 'vue'
import { priorityMeta } from '../composables/utils'

const props = defineProps<{ priority: TodoPriority }>()
const meta = computed(() => priorityMeta(props.priority))
</script>

<template>
  <YdTag :variant="meta.variant">
    {{ meta.label }}
  </YdTag>
</template>
