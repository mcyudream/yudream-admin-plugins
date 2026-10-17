<script setup lang="ts">
import type { TodoTask } from '../types'
import { computed } from 'vue'
import { dueText } from '../composables/utils'

const props = defineProps<{ task: TodoTask }>()
const text = computed(() => dueText(props.task))
</script>

<template>
  <span class="todo-due" :class="{ 'is-overdue': task.overdue, 'is-done': task.status === 'done' }">
    {{ text }}
  </span>
</template>

<style scoped>
.todo-due {
  color: var(--color-text-2, #4e5969);
  font-size: 12px;
}

.todo-due.is-overdue {
  color: rgb(var(--danger-6));
  font-weight: 600;
}

.todo-due.is-done {
  color: var(--color-text-3, #86909c);
}
</style>
