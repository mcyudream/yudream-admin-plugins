<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoSubTask, TodoTableRow, TodoTask } from '../types'
import { YdLoadingOverlay, YdAction, YdButton, YdIcon, YdInput, YdPagination, YdProgress, YdRelatedEntity, YdResponsiveEnhancedTable, YdSelect, YdTag, YdTablePageLayout, YdTooltip, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import DueLabel from '../components/DueLabel.vue'
import PriorityTag from '../components/PriorityTag.vue'
import TaskFormModal from '../components/TaskFormModal.vue'
import UserPicker from '../components/UserPicker.vue'
import { createTodoAdminApi } from '../api/todo-api'
import { PRIORITY_FILTER_OPTIONS, STATUS_OPTIONS, errorMessage } from '../composables/utils'
import { useUserDirectory } from '../composables/useUserDirectory'

/** 清单管理：跨用户查看与维护待办，跟进各部门执行情况。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const adminApi = createTodoAdminApi(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()
// 小任务负责人只回传 ID，展示时按 ID 批量回查昵称。
const directory = useUserDirectory(props.sdk, 'admin')

const loading = ref(false)
const rows = ref<TodoTask[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', status: '', priority: '', overdue: false, archived: false })
const ownerIds = ref<string[]>([])
const stats = reactive({ total: 0, pending: 0, done: 0, overdue: 0, archived: 0 })

const editorOpen = ref(false)
const editingTask = ref<TodoTask | null>(null)

const ownerFilter = computed({
  get: () => ownerIds.value,
  set: (value: string[]) => {
    ownerIds.value = value
    applyFilters()
  },
})

/** 完成率由管理统计推导，仅用于展示。 */
const completionRate = computed(() => (stats.total > 0 ? Math.round((stats.done / stats.total) * 100) : 0))

/** 当前快捷筛选范围：与统计卡的激活态一一对应。 */
const quickRange = computed(() => {
  if (filters.archived) {
    return 'archived'
  }
  if (filters.overdue) {
    return 'overdue'
  }
  if (filters.status === 'pending') {
    return 'pending'
  }
  if (filters.status === 'done') {
    return 'done'
  }
  return 'all'
})

/**
 * 状态选择框的映射模型：「已逾期」不是后端的 status 枚举，而是 status 为空 + overdue 标记，
 * 这里在 v-model 层完成翻译，选择框与统计卡（quickRange）因此始终指向同一个筛选范围。
 */
const statusModel = computed({
  get: () => {
    if (filters.archived) {
      return 'archived'
    }
    return filters.overdue ? 'overdue' : filters.status
  },
  set: (value: string) => {
    filters.archived = value === 'archived'
    filters.overdue = value === 'overdue'
    filters.status = value === 'overdue' || value === 'archived' || value === '' ? '' : value
  },
})

const columns: TableColumn<TodoTableRow>[] = [
  { id: 'title', header: '待办', minWidth: 220, fixed: 'left' },
  { id: 'priority', header: '优先级', width: 100 },
  { id: 'due', header: '截止时间', width: 170 },
  { id: 'source', header: '创建者', width: 130 },
  { id: 'owner', header: '负责人', width: 180 },
  { accessorKey: 'updatedAtLabel', header: '更新时间', width: 150 },
]

/**
 * 行操作统一收进操作菜单：编辑是常规动作，删除是危险动作但保持既有确认流程。
 *
 * <p>小任务子行不是操作对象（管理端没有小任务编辑接口），行级 visible 过滤后整组菜单消失，
 * YdResponsiveEnhancedTable 对空菜单行不渲染操作触发按钮。
 */
const rowActions: ActionMenuItem<TodoTableRow>[] = [
  { label: '编辑', icon: 'morph:pencil', visible: row => !row.subTaskParentId, onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: row => !row.subTaskParentId, onClick: row => confirmDelete(row) },
]

/**
 * 表格数据：父任务行 + 折叠的小任务子行。
 *
 * <p>小任务改为树型表格的独立行（默认折叠，点父任务首列箭头展开），不再把父任务行整体撑高；
 * 管理端没有小任务勾选接口，因此子行只做只读展示。
 */
const tableRows = computed<TodoTableRow[]>(() =>
  rows.value.map(task => ({
    ...task,
    children: (task.subTasks ?? []).map(subTask => toSubRow(subTask, task)),
  })),
)

/** 把一条小任务包装成可复用的表格行：借用父任务的展示字段，只替换与本行相关的部分。 */
function toSubRow(subTask: TodoSubTask, parent: TodoTask): TodoTableRow {
  // 小任务负责人未单独指派过（历史数据为空）时回退为父任务参与人。
  const assignees = subTask.assigneeIds ?? []
  return {
    ...parent,
    id: subTask.id,
    title: subTask.title,
    remark: '',
    status: subTask.done ? 'done' : 'pending',
    overdue: false,
    completedAt: 0,
    completedAtLabel: '',
    updatedAt: subTask.updatedAt ?? 0,
    updatedAtLabel: subTask.updatedAtLabel || '',
    subTasks: [],
    subTaskTotal: 0,
    subTaskDone: 0,
    // 子行只展示小任务自身负责人：回退父任务参与人会让"未指派"的小任务看似带了负责人，
    // 且编辑页里删不掉（编辑页读的是空的 assigneeIds），造成"改不动"的错觉。
    participantIds: assignees,
    // 优先级/截止时间未单独设置时回退父任务值，子行相应列展示「跟随任务」
    priority: subTask.priority || parent.priority,
    subFollowPriority: !subTask.priority,
    dueAt: subTask.dueAt ?? parent.dueAt,
    subFollowDue: subTask.dueAt == null,
    subTaskParentId: parent.id,
    children: undefined,
  }
}

/** 小任务子行整行弱化，与父任务行区分开。 */
function rowClass(row: { original: TodoTableRow }): string {
  return row.original.subTaskParentId ? 'todo-subtask-row' : ''
}

/** 关键词随输入自动搜索（400ms 防抖），回车立即执行。 */
let keywordTimer: ReturnType<typeof setTimeout> | null = null

watch(() => filters.keyword, () => {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
  }
  keywordTimer = setTimeout(() => {
    keywordTimer = null
    applyFilters()
  }, 400)
})

onMounted(() => {
  void Promise.all([load(), loadStats()])
})

async function loadStats() {
  try {
    const data = await adminApi.stats()
    stats.total = data.total
    stats.pending = data.pending
    stats.done = data.done
    stats.overdue = data.overdue
    stats.archived = data.archived ?? 0
  }
  catch {
    // 统计为辅助信息，失败时保留上一次数值
  }
}

async function load() {
  loading.value = true
  try {
    const data = await adminApi.tasks({
      keyword: filters.keyword,
      status: filters.status,
      priority: filters.priority,
      overdue: filters.overdue ? 'true' : '',
      archived: filters.archived ? 'true' : 'false',
      ownerId: ownerIds.value[0] ?? '',
      page: pager.page,
      size: pager.size,
    })
    rows.value = data.records ?? []
    pager.total = data.total ?? 0
    ensureSubTaskAssignees()
    if (rows.value.length === 0 && pager.page > 1) {
      pager.page = Math.max(1, Math.ceil(pager.total / pager.size))
      await load()
    }
  }
  catch (cause: unknown) {
    rows.value = []
    pager.total = 0
    toast.error(errorMessage(cause, '待办列表加载失败'))
  }
  finally {
    loading.value = false
  }
}

/** 任务创建者展示：管理员派发显示派发管理员，其余显示归属用户（即创建者）。 */
function creatorLabel(task: TodoTask) {
  if (task.source === 'admin') {
    return task.assignedByName || directory.labels([task.assignedBy ?? ''])[0] || task.assignedBy || '未知'
  }
  return task.ownerName || directory.labels([task.ownerId])[0] || task.ownerId
}

/** +N 徽标的悬浮提示：列出全部负责人，避免只看得到数量看不到是谁。 */
function allAssignees(task: TodoTableRow): string {
  return directory.labels(task.participantIds ?? []).join('、') || '仅自己'
}

/** 批量回查小任务负责人昵称（负责人已归属参与人，历史数据回退展示参与人）。 */
function ensureSubTaskAssignees() {
  const ids = rows.value.flatMap(task => [
    task.ownerId,
    task.assignedBy ?? '',
    ...(task.participantIds ?? []),
    ...(task.subTasks ?? []).flatMap(subTask => subTask.assigneeIds ?? []),
  ])
  if (ids.length > 0) {
    void directory.ensure(ids)
  }
}

function applyFilters() {
  pager.page = 1
  void load()
}

/** 回车立即搜索：清掉尚未触发的防抖定时器，避免同值重复请求。 */
function searchNow() {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
    keywordTimer = null
  }
  applyFilters()
}

/** 统计卡快捷筛选：点击即把列表切到对应范围，再次点击当前范围则回到「全部」。 */
function applyQuickFilter(range: 'all' | 'pending' | 'overdue' | 'done' | 'archived') {
  const target = quickRange.value === range ? 'all' : range
  filters.overdue = target === 'overdue'
  filters.archived = target === 'archived'
  filters.status = target === 'pending' || target === 'done' ? target : ''
  applyFilters()
}

function onPageChange() {
  void load()
}

function onSizeChange() {
  pager.page = 1
  void load()
}

function openEdit(task: TodoTask) {
  editingTask.value = task
  editorOpen.value = true
}

function onChanged() {
  void Promise.all([load(), loadStats()])
}

function confirmDelete(task: TodoTask) {
  confirm.confirm({
    title: '删除待办',
    content: `确认删除「${task.title}」吗？该用户的清单中将不再显示这条任务，且不可恢复。`,
    onConfirm: async () => {
      try {
        await adminApi.remove(task.id)
        toast.success('待办已删除')
        await Promise.all([load(), loadStats()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

/** 负责人 peek 卡的次要标识：回查到档案用邮箱，否则留空（触发器只显示名字）。 */
</script>

<template>
  <div class="todo-plugin h-full min-h-0">
    <YdTablePageLayout
      title="清单管理"
      stats-title="待办统计与快捷筛选"
      :stats-default-expanded="true"
      :search-default-folded="false"
    >
      <!-- 统计卡即快捷筛选：与状态选择框共用同一筛选范围，点卡片即切换、再点恢复全部。 -->
      <template #stats>
        <div class="todo-plugin__stat-strip" role="group" aria-label="待办统计与快捷筛选">
          <YdAction
            type="button"
            class="todo-stat"
            :class="{ 'is-active': quickRange === 'all' }"
            :aria-pressed="quickRange === 'all'"
            @click="applyQuickFilter('all')"
          >
            <span class="todo-stat__head">
              <i class="todo-stat__dot" aria-hidden="true" />
              <YdIcon name="i-ri:file-list-3-line" class="todo-stat__icon" />
              全部待办
            </span>
            <strong>{{ stats.total }}</strong>
          </YdAction>
          <YdAction
            type="button"
            class="todo-stat is-pending"
            :class="{ 'is-active': quickRange === 'pending' }"
            :aria-pressed="quickRange === 'pending'"
            @click="applyQuickFilter('pending')"
          >
            <span class="todo-stat__head">
              <i class="todo-stat__dot is-warning" aria-hidden="true" />
              <YdIcon name="i-ri:timer-line" class="todo-stat__icon" />
              进行中
            </span>
            <strong>{{ stats.pending }}</strong>
          </YdAction>
          <YdAction
            type="button"
            class="todo-stat is-overdue"
            :class="{ 'is-active': quickRange === 'overdue' }"
            :aria-pressed="quickRange === 'overdue'"
            @click="applyQuickFilter('overdue')"
          >
            <span class="todo-stat__head">
              <i class="todo-stat__dot is-danger" aria-hidden="true" />
              <YdIcon name="i-ri:alarm-warning-line" class="todo-stat__icon" />
              已逾期
            </span>
            <strong>{{ stats.overdue }}</strong>
          </YdAction>
          <YdAction
            type="button"
            class="todo-stat is-done"
            :class="{ 'is-active': quickRange === 'done' }"
            :aria-pressed="quickRange === 'done'"
            :title="`已完成 ${stats.done} 条，完成率 ${completionRate}%`"
            @click="applyQuickFilter('done')"
          >
            <span class="todo-stat__head">
              <i class="todo-stat__dot is-success" aria-hidden="true" />
              <YdIcon name="i-ri:checkbox-circle-line" class="todo-stat__icon" />
              已完成
            </span>
            <strong>{{ stats.done }}</strong>
            <YdProgress
              class="todo-stat__progress"
              :model-value="completionRate"
              aria-label="完成率进度"
            />
          </YdAction>
          <YdAction
            type="button"
            class="todo-stat"
            :class="{ 'is-active': quickRange === 'archived' }"
            :aria-pressed="quickRange === 'archived'"
            :title="`已归档 ${stats.archived} 条，归档任务仅在「已归档」筛选下可见`"
            @click="applyQuickFilter('archived')"
          >
            <span class="todo-stat__head">
              <i class="todo-stat__dot is-archived" aria-hidden="true" />
              <YdIcon name="i-ri:archive-line" class="todo-stat__icon" />
              已归档
            </span>
            <strong>{{ stats.archived }}</strong>
          </YdAction>
        </div>
      </template>

      <template #search>
        <div class="todo-plugin__filters">
          <YdInput
            v-model="filters.keyword"
            class="todo-plugin__filters-keyword"
            placeholder="搜索标题或备注，输入后自动搜索"
            clearable
            @keydown.enter="searchNow"
          />
          <YdSelect
            v-model="statusModel"
            class="w-full todo-plugin__filters-select"
            :options="STATUS_OPTIONS"
            @change="applyFilters"
          />
          <YdSelect
            v-model="filters.priority"
            class="w-full todo-plugin__filters-select"
            :options="PRIORITY_FILTER_OPTIONS"
            @change="applyFilters"
          />
          <UserPicker
            v-model="ownerFilter"
            class="todo-plugin__filters-picker"
            :sdk="sdk"
            :multiple="false"
            placeholder="按负责人筛选"
          />
          <div class="todo-plugin__filter-actions">
            <YdButton
              variant="outline"
              :disabled="quickRange === 'all' && !filters.keyword && !filters.priority && ownerIds.length === 0"
              @click="filters.keyword = ''; filters.status = ''; filters.priority = ''; filters.overdue = false; filters.archived = false; ownerIds = []; applyFilters()"
            >
              重置
            </YdButton>
            <YdButton @click="applyFilters">
              <YdIcon name="i-ri:search-line" />
              查询
            </YdButton>
          </div>
        </div>
      </template>

      <template #table>
        <YdLoadingOverlay :loading="loading">
          <YdResponsiveEnhancedTable
            :columns="columns"
            :data="tableRows"
            :row-class="rowClass"
            row-key="id"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[1040px]"
            border
            stripe
            tree
            column-visibility
            :actions="rowActions"
            empty-text="没有符合筛选条件的待办"
          >
            <template #cell-title="{ row }">
              <div class="todo-title-cell">
                <span
                  v-if="row.original.subTaskParentId"
                  class="todo-subtask-mark"
                  :class="{ 'is-done': row.original.status === 'done' }"
                  aria-hidden="true"
                >
                  <YdIcon v-if="row.original.status === 'done'" name="i-ri:check-line" />
                </span>
                <div class="todo-title-cell__text">
                  <span class="todo-title-cell__row">
                    <span class="todo-title-cell__title" :title="row.original.title">{{ row.original.title }}</span>
                    <YdTooltip v-if="row.original.subTaskTotal > 0" text="点行首箭头展开小任务（管理端子行只读展示）">
                      <span class="todo-subtask-badge">
                        小任务 {{ row.original.subTaskDone }}/{{ row.original.subTaskTotal }}
                      </span>
                    </YdTooltip>
                  </span>
                  <span
                    v-if="!row.original.subTaskParentId && row.original.remark"
                    class="todo-title-cell__remark"
                    :title="row.original.remark"
                  >
                    {{ row.original.remark }}
                  </span>
                </div>
              </div>
            </template>

            <template #cell-owner="{ row }">
              <div v-if="(row.original.participantIds ?? []).length" class="todo-assignees">
                <YdRelatedEntity
                  v-for="pid in (row.original.participantIds ?? []).slice(0, 3)"
                  :key="pid"
                  entity-type="user"
                  :entity-id="pid"
                  :label="directory.labels([pid])[0]"
                  :avatar="directory.option(pid)?.avatar || ''"
                />
                <YdTooltip v-if="(row.original.participantIds ?? []).length > 3" :text="allAssignees(row.original)">
                  <span class="todo-assignee-chip is-more">+{{ (row.original.participantIds ?? []).length - 3 }}</span>
                </YdTooltip>
              </div>
              <span v-else class="todo-assignees__empty">
                {{ row.original.subTaskParentId ? '未指定' : '仅自己' }}
              </span>
            </template>

            <template #cell-priority="{ row }">
              <span v-if="row.original.subTaskParentId && row.original.subFollowPriority" class="todo-subtask-follow">跟随任务</span>
              <PriorityTag v-else :priority="row.original.priority" />
            </template>

            <template #cell-due="{ row }">
              <span v-if="row.original.subTaskParentId && row.original.subFollowDue" class="todo-subtask-follow">跟随任务</span>
              <DueLabel v-else :task="row.original" />
            </template>

            <template #cell-source="{ row }">
              <YdTag v-if="!row.original.subTaskParentId" :variant="row.original.source === 'admin' ? 'default' : 'secondary'">
                {{ creatorLabel(row.original) }}
              </YdTag>
              <span v-else class="todo-subtask-follow">跟随任务</span>
            </template>

            <template #cell-updatedAtLabel="{ row }">
              {{ row.original.updatedAtLabel }}
            </template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
      </template>

      <template #footer>
        <YdPagination
          v-model:page="pager.page"
          v-model:size="pager.size"
          :total="pager.total"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </YdTablePageLayout>

    <TaskFormModal v-model:open="editorOpen" :sdk="sdk" :task="editingTask" surface="admin" @saved="onChanged" />
  </div>
</template>

<style scoped>
.todo-title-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.todo-title-cell__text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

/* 待办名与小任务统计数同行：名称占剩余宽度省略，统计数固定不换行。 */
.todo-title-cell__row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.todo-title-cell__row .todo-title-cell__title {
  flex: 0 1 auto;
  min-width: 0;
}

.todo-title-cell__title {
  overflow: hidden;
  color: var(--color-text-1, #1d2129);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-subtask-badge {
  flex: none;
  cursor: help;
  padding: 0 6px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: 999px;
  background: var(--color-fill-1, #f7f8fa);
  color: var(--color-text-3, #86909c);
  font-size: 11px;
  line-height: 16px;
}

/* 管理端没有小任务勾选接口，子行的完成状态只读展示。 */
.todo-subtask-mark {
  display: grid;
  place-items: center;
  flex: none;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  border: 1.5px solid var(--color-border-3, #c9cdd4);
  border-radius: 6px;
  color: #fff;
  font-size: 12px;
}

.todo-subtask-mark.is-done {
  border-color: rgb(var(--success-6));
  background: rgb(var(--success-6));
}

.todo-assignees {
  display: flex;
  flex-wrap: wrap;
  gap: var(--todo-sp-1);
  min-width: 0;
}

.todo-assignee-chip {
  display: inline-flex;
  align-items: center;
  max-width: 120px;
  padding: 1px var(--todo-sp-3);
  height: 20px;
  border-radius: var(--todo-radius-pill);
  background: var(--color-fill-2, #f2f3f5);
  color: var(--color-text-2, #4e5969);
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-assignee-chip.is-more {
  background: var(--color-fill-1, #f7f8fa);
  color: var(--color-text-3, #86909c);
}

.todo-assignees__empty {
  color: var(--color-text-4, #c9cdd4);
  font-size: 11px;
}

.todo-title-cell__remark {
  overflow: hidden;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 负责人收进 peek 触发器：允许换行，触发器内部自行省略。 */
.todo-assignees :deep(.yd-entity-peek__trigger) {
  max-width: 100%;
  padding-inline: 0;
}
</style>
