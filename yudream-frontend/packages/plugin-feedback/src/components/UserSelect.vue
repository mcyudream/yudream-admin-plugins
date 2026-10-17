<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FeedbackUserOption, FeedbackUserScope } from '../types'
import { YdAction, YdButton, YdIcon, YdInput, YdModal, useYdToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createFeedbackAdminApi } from '../api/feedback-api'
import { errorMessage, userDisplayName } from '../composables/utils'

/**
 * 成员选择器（管理端）：从宿主权威用户目录搜索并选择，禁止手输内部用户 ID。
 *
 * <p>用于「指派处理人」（`multiple`）与「按提交人 / 处理人筛选」（单选）。
 * 数据源是账号目录信息，不含任何反馈内容，因此不会扩大调用者的数据范围。
 *
 * <p>模型恒为 `string[]`：处理人本身是集合，筛选项也可以统一成「零到一人的名单」，
 * 这样单选与多选共用一套渲染与回显逻辑，父组件不必为两种形态各写一遍。
 * 单选语义由 `multiple=false` 承担——点一个成员就替换整个名单并关闭弹窗。
 *
 * <p>`scope="manage"` 用于「处理人」侧：后端返回账号目录候选与提示。宿主 SPI 不提供按权限
 * 反查用户，因此候选未按管理权限预筛，管理员需确认所选成员可访问管理页；账号目录不可用时
 * `available=false`，这时不列候选人也不允许选择，只把原因显示出来，避免静默放开全员。
 */
const props = withDefaults(defineProps<{
  sdk: YuDreamPluginSdk
  /** 已选成员 ID 名单；单选模式下长度不超过 1。 */
  modelValue: string[]
  /** 多选模式：点击成员即增删，弹窗保持打开。 */
  multiple?: boolean
  /** 多选模式下的选择上限。 */
  max?: number
  placeholder?: string
  title?: string
  /** 允许清空选择（处理人可取消指派、筛选项可重置）。 */
  clearable?: boolean
  disabled?: boolean
  /** 候选范围：all=全量账号目录；manage=处理人场景（附带可用标记与权限提示）。 */
  scope?: FeedbackUserScope
}>(), {
  multiple: false,
  max: 8,
  placeholder: '选择成员',
  title: '选择成员',
  clearable: true,
  disabled: false,
  scope: 'all',
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void
}>()

const api = createFeedbackAdminApi(props.sdk)
const toast = useYdToast()

const open = ref(false)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const results = ref<FeedbackUserOption[]>([])
const notice = ref('')
const available = ref(true)
const page = ref(1)
const pageSize = 10
const hasNext = ref(false)
const known = ref<Record<string, FeedbackUserOption>>({})

const selectedIds = computed<string[]>(() => (props.modelValue ?? []).filter(item => Boolean(item)))

const hasSelection = computed(() => selectedIds.value.length > 0)

/** 后端账号目录不可用：此时列表必为空，既不列候选人也不允许翻页。 */
const blocked = computed(() => !available.value)

/** 单选模式展示名字本身，多选模式只报数量，具体名单由下方胶囊承载。 */
const summary = computed(() => selectedIds.value.map(displayNameOf).join('、'))

function remember(users: FeedbackUserOption[]) {
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

/** 已选成员昵称按需回查，避免管理员面对一串内部 ID。 */
async function resolveUnknown(ids: string[]) {
  const missing = ids.filter(id => id && !known.value[id])
  if (missing.length === 0) {
    return
  }
  try {
    const response = await api.resolveUsers(missing)
    remember(response.records ?? [])
  }
  catch {
    // 回查失败时保留 ID 展示，不阻断主流程
  }
}

watch(open, value => {
  if (value) {
    page.value = 1
    void search()
  }
})

watch(selectedIds, ids => void resolveUnknown(ids), { immediate: true })

async function search() {
  loading.value = true
  error.value = ''
  try {
    const response = await api.users(keyword.value.trim(), page.value, pageSize, props.scope)
    results.value = response.records ?? []
    remember(response.records ?? [])
    available.value = response.available !== false
    notice.value = response.notice ?? ''
    hasNext.value = available.value && results.value.length >= pageSize
  }
  catch (cause: unknown) {
    results.value = []
    hasNext.value = false
    error.value = errorMessage(cause, '成员列表加载失败')
  }
  finally {
    loading.value = false
  }
}

function searchFromFirstPage() {
  page.value = 1
  void search()
}

function changePage(next: number) {
  page.value = next
  void search()
}

function isSelected(userId: string): boolean {
  return selectedIds.value.includes(userId)
}

function pick(user: FeedbackUserOption) {
  remember([user])
  if (!props.multiple) {
    emit('update:modelValue', [user.id])
    open.value = false
    return
  }
  if (isSelected(user.id)) {
    emit('update:modelValue', selectedIds.value.filter(id => id !== user.id))
    return
  }
  if (selectedIds.value.length >= props.max) {
    toast.warning(`最多只能选择 ${props.max} 位处理人`)
    return
  }
  emit('update:modelValue', [...selectedIds.value, user.id])
}

function removeSelected(userId: string) {
  emit('update:modelValue', selectedIds.value.filter(id => id !== userId))
}

function clear() {
  emit('update:modelValue', [])
  open.value = false
}
</script>

<template>
  <div class="fb-picker">
    <div class="fb-picker__trigger">
      <YdAction type="button" class="fb-picker__button" :disabled="disabled" @click="open = true">
        <YdIcon name="i-ri:user-search-line" />
        <span v-if="hasSelection && !multiple">{{ summary }}</span>
        <span v-else-if="hasSelection" class="fb-picker__count">已选 {{ selectedIds.length }} 人</span>
        <span v-else class="fb-picker__placeholder">{{ placeholder }}</span>
        <YdIcon
          v-if="hasSelection && clearable"
          name="i-ri:close-circle-line"
          class="fb-picker__clear"
          @click.stop="clear"
        />
      </YdAction>
    </div>

    <div v-if="multiple && hasSelection" class="fb-picker__chips">
      <span v-for="id in selectedIds" :key="id" class="fb-picker__chip">
        <span class="fb-picker__chip-text">{{ displayNameOf(id) }}</span>
        <YdIcon name="i-ri:close-line" class="fb-picker__chip-remove" @click="removeSelected(id)" />
      </span>
    </div>

    <YdModal v-model="open" :title="title" width="560px" :footer="false">
      <div class="fb-picker__dialog">
        <div class="fb-picker__search">
          <YdInput
            v-model="keyword"
            placeholder="搜索姓名、用户名或邮箱"
            clearable
            @keydown.enter="searchFromFirstPage"
          />
          <YdButton :loading="loading" @click="searchFromFirstPage">
            搜索
          </YdButton>
        </div>

        <p v-if="error" class="fb-picker__error">{{ error }}</p>

        <div v-else-if="blocked" class="fb-picker__state fb-picker__state--notice">
          {{ notice || '当前无法加载成员目录，请稍后重试' }}
        </div>

        <div v-else-if="loading && results.length === 0" class="fb-picker__state">
          正在加载成员
        </div>

        <div v-else-if="results.length === 0" class="fb-picker__state">
          {{ notice || '没有匹配的成员，换个关键词试试' }}
        </div>

        <ul v-else class="fb-picker__list">
          <li v-if="notice" class="fb-picker__hint">{{ notice }}</li>
          <li v-for="user in results" :key="user.id">
            <YdAction
              type="button"
              class="fb-picker__option"
              :class="{ 'is-selected': isSelected(user.id) }"
              @click="pick(user)"
            >
              <span class="fb-picker__avatar">{{ userDisplayName(user).slice(0, 1) }}</span>
              <span class="fb-picker__identity">
                <strong>{{ userDisplayName(user) }}</strong>
                <small>{{ user.username || user.email || user.id }}</small>
              </span>
              <YdIcon v-if="isSelected(user.id)" name="i-ri:check-line" class="fb-picker__check" />
            </YdAction>
          </li>
        </ul>

        <div v-if="!blocked" class="fb-picker__pager">
          <YdAction type="button" :disabled="page <= 1 || loading" @click="changePage(page - 1)">
            上一页
          </YdAction>
          <span>第 {{ page }} 页</span>
          <YdAction type="button" :disabled="!hasNext || loading" @click="changePage(page + 1)">
            下一页
          </YdAction>
        </div>

        <div class="fb-picker__dialog-actions">
          <YdButton v-if="clearable" variant="outline" @click="clear">
            清除选择
          </YdButton>
          <YdButton variant="outline" @click="open = false">
            {{ multiple ? '完成' : '取消' }}
          </YdButton>
        </div>
      </div>
    </YdModal>
  </div>
</template>

<style scoped>
.fb-picker {
  display: grid;
  gap: var(--fb-sp-2);
  min-width: 0;
}

.fb-picker__button {
  display: inline-flex;
  align-items: center;
  gap: var(--fb-sp-2);
  width: 100%;
  min-width: 0;
  padding: 7px 12px;
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface);
  color: var(--fb-text-1);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease;
}

.fb-picker__button:hover:not(:disabled) {
  border-color: var(--fb-text-4);
}

.fb-picker__button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fb-picker__button > span {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-picker__placeholder {
  color: var(--fb-text-3);
}

.fb-picker__count {
  color: var(--fb-text-2);
}

.fb-picker__clear {
  margin-left: auto;
  flex: none;
  color: var(--fb-text-3);
}

.fb-picker__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fb-sp-1);
}

.fb-picker__chip {
  display: inline-flex;
  align-items: center;
  gap: var(--fb-sp-1);
  max-width: 100%;
  padding: 1px var(--fb-sp-2);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-pill);
  background: var(--fb-surface-muted);
  color: var(--fb-text-2);
  font-size: 12px;
  line-height: 20px;
}

.fb-picker__chip-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-picker__chip-remove {
  flex: none;
  color: var(--fb-text-3);
  cursor: pointer;
}

.fb-picker__chip-remove:hover {
  color: var(--fb-text-1);
}

.fb-picker__dialog {
  display: grid;
  gap: var(--fb-sp-4);
}

.fb-picker__search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--fb-sp-3);
}

.fb-picker__state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--fb-text-3);
  font-size: 13px;
}

.fb-picker__error {
  margin: 0;
  color: rgb(var(--danger-6));
  font-size: 12px;
}

/* 权限无法确认时的说明：与「加载中 / 无匹配」不同，这里承载的是一句解释，按段落排。 */
.fb-picker__state--notice {
  padding: var(--fb-sp-3) var(--fb-sp-4);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface-muted);
  color: var(--fb-text-2);
  line-height: 1.6;
  text-align: center;
}

/* 候选列表顶部的权限提示：候选未按管理权限预筛，管理员需自行确认。 */
.fb-picker__hint {
  padding: var(--fb-sp-2) var(--fb-sp-3);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface-muted);
  color: var(--fb-text-2);
  font-size: 12px;
  line-height: 1.6;
  list-style: none;
}

.fb-picker__list {
  display: grid;
  gap: var(--fb-sp-2);
  max-height: 320px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.fb-picker__option {
  display: flex;
  align-items: center;
  gap: var(--fb-sp-3);
  width: 100%;
  padding: var(--fb-sp-3) var(--fb-sp-4);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.fb-picker__option:hover,
.fb-picker__option.is-selected {
  border-color: var(--color-primary-6, #165dff);
}

.fb-picker__option.is-selected {
  background: var(--color-primary-light-1, #e8f3ff);
}

.fb-picker__avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: var(--fb-radius-pill);
  background: var(--color-primary-6, #165dff);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.fb-picker__identity {
  display: grid;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.fb-picker__identity strong {
  overflow: hidden;
  color: var(--fb-text-1);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-picker__identity small {
  overflow: hidden;
  color: var(--fb-text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-picker__check {
  color: var(--color-primary-6, #165dff);
}

.fb-picker__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fb-sp-4);
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-picker__pager button {
  padding: 4px 10px;
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface);
  color: var(--fb-text-1);
  font-size: 12px;
  cursor: pointer;
}

.fb-picker__pager button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.fb-picker__dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--fb-sp-3);
}
</style>
