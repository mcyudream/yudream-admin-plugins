<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoSubTaskInput } from '../types'
import { YdButton, YdIcon, YdInput, YdSelect, YdDatePicker } from '@yudream/components'
import { computed, nextTick, ref } from 'vue'
import UserPicker from './UserPicker.vue'
import { PRIORITY_OPTIONS, parseDateTimeInput, toDateTimeInput } from '../composables/utils'

const props = defineProps<{
  modelValue: TodoSubTaskInput[]
  disabled?: boolean
  surface?: 'user' | 'admin'
  sdk: YuDreamPluginSdk
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: TodoSubTaskInput[]): void
}>()

const MAX_COUNT = 30

const PRIORITY_FOLLOW_OPTIONS = [
  { label: '跟随任务优先级', value: '' },
  ...PRIORITY_OPTIONS,
]

const full = computed(() => props.modelValue.length >= MAX_COUNT)
const listEl = ref<HTMLElement | null>(null)

function commit(next: TodoSubTaskInput[]) {
  emit('update:modelValue', next)
}

function patch(index: number, changes: Partial<TodoSubTaskInput>) {
  commit(props.modelValue.map((item, i) => (i === index ? { ...item, ...changes } : item)))
}

function updateTitle(index: number, title: string) {
  patch(index, { title })
}

function updatePriority(index: number, priority: string) {
  patch(index, { priority: priority || undefined })
}

function updateDue(index: number, dueInput: string) {
  patch(index, { dueAt: parseDateTimeInput(dueInput) })
}

function updateAssignees(index: number, assigneeIds: string[]) {
  patch(index, { assigneeIds })
}

function add() {
  if (full.value || props.disabled) {
    return
  }
  commit([...props.modelValue, { title: '', assigneeIds: [] }])
  // 新增后把光标移到新出现的输入框，避免用户手动重新点选。
  nextTick(focusLast)
}

function focusLast() {
  // 只聚焦最后一行的标题输入框：行内其他控件（优先级/截止时间/负责人）各自也渲染 input，
  // 按"最后一个 input"取会落到截止日期框上。
  const titleInput = listEl.value?.querySelector<HTMLInputElement>(
    '.todo-subtask-editor__row:last-child .todo-subtask-editor__main input',
  )
  titleInput?.focus()
}

function removeAt(index: number) {
  if (props.disabled) {
    return
  }
  commit(props.modelValue.filter((_, i) => i !== index))
}
</script>

<template>
  <div class="todo-subtask-editor">
    <ul v-if="modelValue.length > 0" ref="listEl" class="todo-subtask-editor__list">
      <li v-for="(item, index) in modelValue" :key="index" class="todo-subtask-editor__row">
        <div class="todo-subtask-editor__main">
          <span class="todo-subtask-editor__index">{{ index + 1 }}</span>
          <YdInput
            :model-value="item.title"
            placeholder="小任务标题"
            :maxlength="120"
            :disabled="disabled"
            @update:model-value="updateTitle(index, $event as string)"
          />
          <YdButton
            variant="ghost"
            size="icon-sm"
            :disabled="disabled"
            :aria-label="`删除第 ${index + 1} 条小任务`"
            @click="removeAt(index)"
          >
            <YdIcon name="i-ri:close-line" />
          </YdButton>
        </div>
        <div class="todo-subtask-editor__attrs">
          <YdSelect
            class="todo-subtask-editor__priority"
            :model-value="item.priority ?? ''"
            :options="PRIORITY_FOLLOW_OPTIONS"
            :disabled="disabled"
            placeholder="跟随任务优先级"
            @update:model-value="updatePriority(index, $event as string)"
          />
          <YdDatePicker
            class="todo-subtask-editor__due"
            :model-value="toDateTimeInput(item.dueAt ?? null)"
            show-time
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD HH:mm"
            placeholder="截止时间跟随任务"
            :disabled="disabled"
            @update:model-value="updateDue(index, ($event ?? '') as string)"
          />
          <UserPicker
            class="todo-subtask-editor__assignees"
            :model-value="item.assigneeIds"
            :sdk="sdk"
            :surface="surface"
            :disabled="disabled"
            title="选择小任务负责人"
            placeholder="负责人跟随参与人"
            @update:model-value="updateAssignees(index, $event)"
          />
        </div>
      </li>
    </ul>

    <div class="todo-subtask-editor__footer">
      <YdButton variant="outline" size="sm" :disabled="disabled || full" @click="add">
        <YdIcon name="i-ri:add-line" />
        添加小任务
      </YdButton>
      <span class="todo-subtask-editor__count">{{ modelValue.length }} / {{ MAX_COUNT }}</span>
    </div>
    <small class="todo-subtask-editor__hint">优先级与截止时间未设置时跟随任务整体；负责人不指派则默认任务参与人。</small>
  </div>
</template>

<style scoped>
.todo-subtask-editor {
  display: grid;
  gap: 8px;
}

.todo-subtask-editor__list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.todo-subtask-editor__row {
  display: grid;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: var(--todo-radius-sm, 6px);
}

.todo-subtask-editor__main {
  display: flex;
  align-items: center;
  gap: 6px;
}

.todo-subtask-editor__attrs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding-left: 24px;
}

.todo-subtask-editor__priority {
  flex: 1 1 130px;
  min-width: 130px;
}

.todo-subtask-editor__due {
  flex: 1 1 150px;
  min-width: 150px;
}

.todo-subtask-editor__assignees {
  flex: 1 1 150px;
  min-width: 150px;
}

.todo-subtask-editor__assignees :deep(.todo-picker__button) {
  width: 100%;
}

.todo-subtask-editor__index {
  flex: none;
  width: 18px;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
  text-align: center;
}

.todo-subtask-editor__footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.todo-subtask-editor__count {
  color: var(--color-text-3, #86909c);
  font-size: 11px;
}

.todo-subtask-editor__hint {
  color: var(--color-text-3, #86909c);
  font-size: 11px;
}
</style>
