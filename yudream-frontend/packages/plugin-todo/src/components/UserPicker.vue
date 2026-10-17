<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoUserOption } from '../types'
import { YdAction, YdButton, YdIcon, YdInput, YdModal } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createTodoAdminApi, createTodoUserApi } from '../api/todo-api'
import { errorMessage, userDisplayName } from '../composables/utils'

/**
 * 人员选择器：从宿主权威用户目录搜索，禁止手输内部用户 ID。
 *
 * <p>用户端（user）用于选择任务参与人，管理端（admin）用于选择任务参与人，两者走各自接口。
 */
const props = withDefaults(defineProps<{
  sdk: YuDreamPluginSdk
  modelValue: string[]
  /** user 走 /me/users，admin 走 /admin/users。 */
  surface?: 'user' | 'admin'
  disabled?: boolean
  multiple?: boolean
  placeholder?: string
  title?: string
  hint?: string
}>(), {
  surface: 'admin',
  disabled: false,
  multiple: true,
  placeholder: '选择成员',
  title: '选择人员',
  hint: '',
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void
}>()

const api = props.surface === 'admin' ? createTodoAdminApi(props.sdk) : createTodoUserApi(props.sdk)

const open = ref(false)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const results = ref<TodoUserOption[]>([])
const page = ref(1)
const pageSize = ref(10)
const hasNext = ref(false)

const known = ref<Record<string, TodoUserOption>>({})

const selected = computed(() => props.modelValue ?? [])
const selectedSet = computed(() => new Set(selected.value))
const total = computed(() => (page.value - 1) * pageSize.value + results.value.length + (hasNext.value ? 1 : 0))

function remember(users: TodoUserOption[]) {
  if (users.length === 0) {
    return
  }
  const next = { ...known.value }
  for (const user of users) {
    next[user.id] = user
  }
  known.value = next
}

function displayNameOf(userId: string): string {
  const user = known.value[userId]
  return user ? userDisplayName(user) : userId
}

/** 已选人员的昵称按需回查，避免管理员面对一串内部 ID。 */
async function resolveUnknown(ids: string[]) {
  const missing = ids.filter(id => !known.value[id])
  if (missing.length === 0) {
    return
  }
  try {
    const response = await api.resolveUsers(missing)
    remember(response.records ?? [])
  }
  catch {
    // 回查失败时保留 ID 展示，不阻断提交流程
  }
}

watch(open, value => {
  if (value) {
    page.value = 1
    void search()
  }
})

watch(selected, ids => void resolveUnknown(ids), { immediate: true })

async function search(resetPage = false) {
  if (resetPage) {
    page.value = 1
  }
  loading.value = true
  error.value = ''
  try {
    const response = await api.users(keyword.value.trim(), page.value, pageSize.value)
    results.value = response.records ?? []
    remember(response.records ?? [])
    hasNext.value = results.value.length >= pageSize.value
  }
  catch (cause: unknown) {
    results.value = []
    hasNext.value = false
    error.value = errorMessage(cause, '人员列表加载失败')
  }
  finally {
    loading.value = false
  }
}

function changePage(next: number) {
  page.value = next
  void search()
}

function toggle(user: TodoUserOption) {
  if (selectedSet.value.has(user.id)) {
    emit('update:modelValue', selected.value.filter(id => id !== user.id))
    return
  }
  if (!props.multiple) {
    emit('update:modelValue', [user.id])
    open.value = false
    return
  }
  emit('update:modelValue', [...selected.value, user.id])
}

function remove(userId: string) {
  emit('update:modelValue', selected.value.filter(id => id !== userId))
}
</script>

<template>
  <div class="todo-picker">
    <div class="todo-picker__trigger">
      <YdAction
        type="button"
        class="todo-picker__button"
        :disabled="disabled"
        @click="open = true"
      >
        <YdIcon name="i-ri:user-add-line" />
        <span v-if="selected.length === 0">{{ placeholder }}</span>
        <span v-else-if="multiple">已选择 {{ selected.length }} 人</span>
        <span v-else>{{ displayNameOf(selected[0]) }}</span>
      </YdAction>
      <p v-if="hint && selected.length === 0 && multiple" class="todo-picker__hint">
        {{ hint }}
      </p>
    </div>

    <div v-if="selected.length > 0" class="todo-picker__selected">
      <span v-for="id in selected" :key="id" class="todo-picker__chip">
        {{ displayNameOf(id) }}
        <YdAction type="button" :disabled="disabled" @click="remove(id)">
          <YdIcon name="i-ri:close-line" />
        </YdAction>
      </span>
    </div>

    <YdModal v-model="open" :title="title" width="560px" :footer="false">
      <div class="todo-picker__dialog">
        <div class="todo-picker__search">
          <YdInput
            v-model="keyword"
            placeholder="搜索姓名、用户名或邮箱"
            clearable
            @keydown.enter="search(true)"
          />
          <YdButton :loading="loading" @click="search(true)">
            搜索
          </YdButton>
        </div>

        <p v-if="error" class="todo-picker__error">
          {{ error }}
        </p>

        <div v-else-if="loading && results.length === 0" class="todo-picker__state">
          <YdIcon name="i-ri:loader-4-line" class="todo-picker__spin" />
          正在加载人员
        </div>

        <div v-else-if="results.length === 0" class="todo-picker__state">
          没有匹配的人员，换个关键词试试
        </div>

        <ul v-else class="todo-picker__list">
          <li v-for="user in results" :key="user.id">
            <YdAction
              type="button"
              class="todo-picker__option"
              :class="{ 'is-selected': selectedSet.has(user.id) }"
              @click="toggle(user)"
            >
              <span class="todo-picker__avatar">{{ userDisplayName(user).slice(0, 1) }}</span>
              <span class="todo-picker__identity">
                <strong>{{ userDisplayName(user) }}</strong>
                <small>{{ user.username || user.email || user.id }}</small>
              </span>
              <YdIcon v-if="selectedSet.has(user.id)" name="i-ri:check-line" class="todo-picker__check" />
            </YdAction>
          </li>
        </ul>

        <div v-if="total > 0" class="todo-picker__pager">
          <YdAction type="button" :disabled="page <= 1 || loading" @click="changePage(page - 1)">
            上一页
          </YdAction>
          <span>第 {{ page }} 页</span>
          <YdAction type="button" :disabled="!hasNext || loading" @click="changePage(page + 1)">
            下一页
          </YdAction>
        </div>

        <div class="todo-picker__dialog-actions">
          <YdButton variant="outline" @click="open = false">
            完成
          </YdButton>
        </div>
      </div>
    </YdModal>
  </div>
</template>

<style scoped>
.todo-picker {
  display: grid;
  gap: 8px;
}

.todo-picker__trigger {
  display: grid;
  gap: 4px;
}

.todo-picker__button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
  padding: 7px 12px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: 8px;
  background: var(--color-bg-2, #fff);
  color: var(--color-text-1, #1d2129);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

/* 触发器与输入框同宽：长昵称截断而不是把相邻筛选控件挤开 */
.todo-picker__button > span {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-picker__button:hover:not(:disabled) {
  border-color: var(--color-border-3, #c9cdd4);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 8%);
  transform: translateY(-1px);
}

.todo-picker__button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.todo-picker__hint,
.todo-picker__error {
  margin: 0;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
}

.todo-picker__error {
  color: rgb(var(--danger-6));
}

.todo-picker__selected {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.todo-picker__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 6px 3px 10px;
  border-radius: 999px;
  background: var(--color-fill-2, #f2f3f5);
  color: var(--color-text-1, #1d2129);
  font-size: 12px;
}

.todo-picker__chip button {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-text-3, #86909c);
  cursor: pointer;
}

.todo-picker__dialog {
  display: grid;
  gap: 12px;
}

.todo-picker__search {
  display: grid;
  gap: 8px;
  grid-template-columns: 1fr auto;
}

.todo-picker__state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 120px;
  color: var(--color-text-3, #86909c);
  font-size: 13px;
}

.todo-picker__spin {
  animation: todo-spin 0.9s linear infinite;
}

.todo-picker__list {
  display: grid;
  gap: 6px;
  max-height: 320px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.todo-picker__option {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-border-1, #e5e6eb);
  border-radius: 8px;
  background: var(--color-bg-2, #fff);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.todo-picker__option:hover {
  border-color: var(--color-primary-6, #165dff);
}

.todo-picker__option.is-selected {
  border-color: var(--color-primary-6, #165dff);
  background: var(--color-primary-light-1, #e8f3ff);
}

.todo-picker__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--color-primary-6, #165dff);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.todo-picker__identity {
  display: grid;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.todo-picker__identity strong {
  overflow: hidden;
  color: var(--color-text-1, #1d2129);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-picker__identity small {
  overflow: hidden;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-picker__check {
  color: var(--color-primary-6, #165dff);
}

.todo-picker__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
}

.todo-picker__pager button {
  padding: 4px 10px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: 6px;
  background: var(--color-bg-2, #fff);
  color: var(--color-text-1, #1d2129);
  font-size: 12px;
  cursor: pointer;
}

.todo-picker__pager button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.todo-picker__dialog-actions {
  display: flex;
  justify-content: flex-end;
}

@keyframes todo-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
