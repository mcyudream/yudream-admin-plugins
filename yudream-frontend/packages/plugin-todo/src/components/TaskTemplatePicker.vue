<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoTemplate } from '../types'
import { YdAction, YdButton, YdIcon, YdInput, YdModal, YdTag, useYdToast } from '@yudream/components'
import { ref, watch } from 'vue'
import { createTodoAdminApi, createTodoUserApi } from '../api/todo-api'
import { errorMessage, priorityMeta } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  /** user 只列出管理员派发的任务，admin 可跨用户复用任意任务作为派发模板。 */
  surface: 'user' | 'admin'
  modelValue: string
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
}>()

const toast = useYdToast()
const open = ref(false)
const loading = ref(false)
const keyword = ref('')
const options = ref<TodoTemplate[]>([])
const picked = ref<TodoTemplate | null>(null)

/** 仅用户端需要拦截「清单中已有」的任务；管理端面向多目标用户，不做该拦截。 */
function disabled(task: TodoTemplate): boolean {
  return props.surface === 'user' && task.inMyList
}

async function load() {
  loading.value = true
  try {
    const api = props.surface === 'admin' ? createTodoAdminApi(props.sdk) : createTodoUserApi(props.sdk)
    const response = await api.templates(keyword.value.trim())
    options.value = response.records ?? []
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '加载可选任务失败，请稍后重试'))
  }
  finally {
    loading.value = false
  }
}

watch(open, value => {
  if (value) {
    keyword.value = ''
    void load()
  }
})

watch(() => props.modelValue, value => {
  if (!value) {
    picked.value = null
  }
})

function choose(task: TodoTemplate) {
  if (disabled(task)) {
    return
  }
  picked.value = task
  emit('update:modelValue', task.id)
  open.value = false
}

function clear() {
  picked.value = null
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="todo-template">
    <div v-if="picked" class="todo-template__picked">
      <div class="todo-template__picked-main">
        <span class="todo-template__picked-title">{{ picked.title }}</span>
        <span v-if="picked.subTaskTotal > 0" class="todo-template__picked-meta">
          含 {{ picked.subTaskTotal }} 条小任务
        </span>
      </div>
      <div class="todo-template__picked-actions">
        <YdButton variant="outline" size="sm" @click="open = true">
          更换
        </YdButton>
        <YdButton variant="ghost" size="sm" @click="clear">
          清除
        </YdButton>
      </div>
    </div>
    <YdButton v-else variant="outline" @click="open = true">
      <YdIcon name="i-ri:file-copy-line" />
      从已有任务选择
    </YdButton>

    <YdModal v-model="open" title="选择已有任务" width="620px" :footer="false">
      <div class="todo-template">
        <YdInput v-model="keyword" placeholder="搜索任务标题或备注" @keydown.enter="load" />

        <div v-if="loading" class="todo-template__state">
          加载中…
        </div>
        <ul v-else-if="options.length > 0" class="todo-template__list">
          <li v-for="task in options" :key="task.id">
            <YdAction
              type="button"
              class="todo-template__option"
              :class="{ 'is-disabled': disabled(task) }"
              :disabled="disabled(task)"
              :title="disabled(task) ? '你的清单中已有同名待办' : ''"
              @click="choose(task)"
            >
              <span class="todo-template__option-title">{{ task.title }}</span>
              <YdTag :variant="priorityMeta(task.priority).variant">
                {{ priorityMeta(task.priority).label }}
              </YdTag>
              <span v-if="task.subTaskTotal > 0" class="todo-template__option-meta">
                {{ task.subTaskTotal }} 条小任务
              </span>
              <span v-if="surface === 'admin' && task.ownerName" class="todo-template__option-owner">
                {{ task.ownerName }}
              </span>
              <span v-if="disabled(task)" class="todo-template__option-exists">
                已在清单中
              </span>
            </YdAction>
          </li>
        </ul>
        <div v-else class="todo-template__state">
          暂无可复用的任务
        </div>
      </div>
    </YdModal>
  </div>
</template>

<style scoped>
.todo-template {
  display: grid;
  gap: 10px;
}

.todo-template__picked {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: 10px;
  background: var(--color-fill-1, #f7f8fa);
}

.todo-template__picked-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.todo-template__picked-title {
  overflow: hidden;
  color: var(--color-text-1, #1d2129);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-template__picked-meta {
  color: var(--color-text-3, #86909c);
  font-size: 11px;
}

.todo-template__picked-actions {
  display: flex;
  flex: none;
  gap: 6px;
}

.todo-template__list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  overflow: auto;
  max-height: 320px;
  list-style: none;
}

.todo-template__option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-border-1, #e5e6eb);
  border-radius: 10px;
  background: var(--color-bg-2, #fff);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}

.todo-template__option:hover:not(.is-disabled) {
  border-color: var(--color-border-3, #c9cdd4);
  background: var(--color-fill-1, #f7f8fa);
  transform: translateY(-1px);
}

.todo-template__option.is-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.todo-template__option-title {
  overflow: hidden;
  flex: 1;
  min-width: 0;
  color: var(--color-text-1, #1d2129);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-template__option-meta,
.todo-template__option-owner {
  flex: none;
  color: var(--color-text-3, #86909c);
  font-size: 11px;
}

.todo-template__option-exists {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-fill-3, #e5e6eb);
  color: var(--color-text-3, #86909c);
  font-size: 11px;
}

.todo-template__state {
  padding: 14px 0;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
  text-align: center;
}
</style>
