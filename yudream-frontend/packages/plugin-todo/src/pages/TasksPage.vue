<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoSubTask, TodoTableRow, TodoTask } from '../types'
import { YdLoadingOverlay, YdAction, YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdPagination, YdProgress, YdSelect, YdTable, YdTag, YdTooltip, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import DueLabel from '../components/DueLabel.vue'
import PriorityTag from '../components/PriorityTag.vue'
import TaskFormModal from '../components/TaskFormModal.vue'
import TaskTemplatePicker from '../components/TaskTemplatePicker.vue'
import { createTodoUserApi } from '../api/todo-api'
import { PRIORITY_FILTER_OPTIONS, STATUS_OPTIONS, errorMessage } from '../composables/utils'
import { useUserDirectory } from '../composables/useUserDirectory'

/** 我的清单：只服务当前登录者自己的待办。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createTodoUserApi(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()
const route = useRoute()
// 参与人只回传 ID，展示时按 ID 批量回查昵称。
const directory = useUserDirectory(props.sdk, 'user')

const loading = ref(false)
const togglingId = ref('')
const joiningSubTaskId = ref('')
const rows = ref<TodoTask[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
// 主页「我的待办」卡片点击某条待办会带上 keyword 跳转过来，落地即定位到该条；
// 初始关键词在建状态时带上，防抖 watch 只响应用户后续输入，避免落地时重复请求。
const initialKeyword = typeof route.query.keyword === 'string' && route.query.keyword.trim()
  ? route.query.keyword.trim()
  : ''
const filters = reactive({ keyword: initialKeyword, status: '', priority: '', overdue: false, archived: false })
const overview = reactive({ total: 0, pending: 0, done: 0, overdue: 0, archived: 0, completionRate: 0 })

const editorOpen = ref(false)
const editingTask = ref<TodoTask | null>(null)

const templateOpen = ref(false)
const templateId = ref('')
const templateSubmitting = ref(false)

const columns: TableColumn<TodoTableRow>[] = [
  { id: 'title', header: '待办', minWidth: 280 },
  { id: 'priority', header: '优先级', width: 100 },
  { id: 'due', header: '截止时间', width: 170 },
  { id: 'source', header: '来源', width: 110 },
  { id: 'assignees', header: '负责人', width: 180 },
  { accessorKey: 'updatedAtLabel', header: '更新时间', width: 150 },
  { id: 'operation', header: '操作', width: 200, fixed: 'right' },
]

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

/**
 * 表格数据：父任务行 + 折叠的小任务子行。
 *
 * <p>小任务不再内联在父任务的单元格里（那样会把父任务行整体撑高），改为树型表格的独立行，
 * 默认折叠，点父任务首列的箭头才展开；展开的每一行都是标准行高。
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
  void Promise.all([load(), loadOverview()])
})

async function loadOverview() {
  try {
    const data = await api.overview()
    overview.total = data.total
    overview.pending = data.pending
    overview.done = data.done
    overview.overdue = data.overdue
    overview.archived = data.archived
    overview.completionRate = data.completionRate
  }
  catch {
    // 概览是辅助信息，失败时保留上一次数值，不打断主列表
  }
}

async function load() {
  loading.value = true
  try {
    const data = await api.tasks({
      keyword: filters.keyword,
      status: filters.status,
      priority: filters.priority,
      overdue: filters.overdue ? 'true' : '',
      archived: filters.archived ? 'true' : 'false',
      page: pager.page,
      size: pager.size,
    })
    rows.value = data.records ?? []
    pager.total = data.total ?? 0
    ensureParticipantNames()
    if (rows.value.length === 0 && pager.page > 1) {
      pager.page = Math.max(1, Math.ceil(pager.total / pager.size))
      await load()
    }
  }
  catch (cause: unknown) {
    rows.value = []
    pager.total = 0
    toast.error(errorMessage(cause, '待办加载失败'))
  }
  finally {
    loading.value = false
  }
}

function applyFilters() {
  pager.page = 1
  void load()
}

/** +N 徽标的悬浮提示：列出全部负责人，避免只看得到数量看不到是谁。 */
function allAssignees(task: TodoTableRow): string {
  return directory.labels(task.participantIds ?? []).join('、') || '仅自己'
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

/** 批量回查当前页所有任务与小任务的负责昵称，避免负责人只显示 ID。 */
function ensureParticipantNames() {
  const ids = rows.value.flatMap(task => [
    task.ownerId,
    ...(task.participantIds ?? []),
    ...(task.subTasks ?? []).flatMap(subTask => subTask.assigneeIds ?? []),
  ])
  if (ids.length > 0) {
    void directory.ensure(ids)
  }
}

function openCreate() {
  editingTask.value = null
  editorOpen.value = true
}

function openEdit(task: TodoTask) {
  editingTask.value = task
  editorOpen.value = true
}

function onSaved() {
  void Promise.all([load(), loadOverview()])
}

/**
 * 勾选父任务或小任务。
 *
 * <p>小任务接口回传的是**父任务**，因此按父任务 id 就地替换整行，让小任务子行随之重建；
 * 树型展开状态按行 id 记忆，替换行不会导致已展开的小任务被收起。
 */
async function toggleDone(row: TodoTableRow) {
  if (togglingId.value) {
    return
  }
  togglingId.value = row.id
  const nextDone = row.status !== 'done'
  const parentId = row.subTaskParentId
  try {
    const updated = parentId
      ? await api.setSubTaskDone(parentId, row.id, nextDone)
      : await api.setDone(row.id, nextDone)
    replaceRow(parentId ?? row.id, updated)
    void loadOverview()
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '状态更新失败'))
  }
  finally {
    togglingId.value = ''
  }
}

/** 就地替换被修改的行：YdTable 的 row.original 是渲染快照，必须换数组引用才会重新同步。 */
function replaceRow(id: string, updated: TodoTask) {
  rows.value = rows.value.map(item => (item.id === id ? updated : item))
}

function openTemplateDialog() {
  templateId.value = ''
  templateOpen.value = true
}

async function submitFromTemplate() {
  if (!templateId.value) {
    toast.error('请先选择要加入的任务')
    return
  }
  if (templateSubmitting.value) {
    return
  }
  templateSubmitting.value = true
  try {
    await api.joinTask(templateId.value)
    toast.success('已加入该任务，可在清单中查看并推进')
    templateOpen.value = false
    await Promise.all([load(), loadOverview()])
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '加入失败，请稍后重试'))
  }
  finally {
    templateSubmitting.value = false
  }
}

/** 来源展示：管理员派发 / 我创建的 / 任务库加入（直接标注原任务创建者用户名）。 */
function sourceLabel(task: TodoTask) {
  if (task.source === 'admin') {
    return '管理员派发'
  }
  return task.owned ? '我创建的' : creatorLabel(task)
}

/** 任务创建者昵称：任务库加入的协作任务归属人即原任务创建者。 */
function creatorLabel(task: TodoTask) {
  return directory.labels([task.ownerId])[0] || task.ownerId
}

/** 退出协作任务：把自己从参与人中移除，之后不再看到该任务（归属人不可退出，只能删除）。 */
function confirmLeave(task: TodoTask) {
  confirm.confirm({
    title: '退出任务',
    content: `确认退出「${task.title}」吗？退出后您将不再看到该任务。`,
    onConfirm: async () => {
      try {
        await api.leave(task.id)
        toast.success('已退出该任务')
        await Promise.all([load(), loadOverview()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '退出失败'))
      }
    },
  })
}

/** 当前用户是否负责该小任务：以回传的 mySubTaskIds 为准，不依赖展示用的负责人回退逻辑。 */
function isSubTaskAssignee(row: TodoTableRow) {
  return (row.mySubTaskIds ?? []).includes(row.id)
}

/** 加入小任务：把自己加入负责人列表，之后负责人列会显示自己。 */
async function joinSubTask(row: TodoTableRow) {
  const parentId = row.subTaskParentId
  if (!parentId || joiningSubTaskId.value) {
    return
  }
  joiningSubTaskId.value = row.id
  try {
    const updated = await api.joinSubTask(parentId, row.id)
    replaceRow(parentId, updated)
    toast.success('已加入该小任务')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '加入失败'))
  }
  finally {
    joiningSubTaskId.value = ''
  }
}

/** 退出小任务：把自己从负责人列表移除，不再负责该小任务。 */
function confirmSubTaskLeave(row: TodoTableRow) {
  const parentId = row.subTaskParentId
  if (!parentId) {
    return
  }
  confirm.confirm({
    title: '退出小任务',
    content: `确认不再负责「${row.title}」吗？`,
    onConfirm: async () => {
      try {
        const updated = await api.leaveSubTask(parentId, row.id)
        replaceRow(parentId, updated)
        toast.success('已退出该小任务')
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '退出失败'))
      }
    },
  })
}

/** 归档/取消归档：仅归属人且任务已完成时可归档；归档后从默认清单、统计与日程中收起。 */
async function toggleArchive(task: TodoTask) {
  const next = !task.archived
  try {
    await api.setArchived(task.id, next)
    toast.success(next ? '任务已归档，可在状态筛选「已归档」中查看' : '任务已恢复到清单')
    await Promise.all([load(), loadOverview()])
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, next ? '归档失败' : '恢复失败'))
  }
}

function confirmDelete(task: TodoTask) {
  confirm.confirm({
    title: '删除待办',
    content: `确认删除「${task.title}」吗？删除后不可恢复。`,
    onConfirm: async () => {
      try {
        await api.remove(task.id)
        toast.success('待办已删除')
        await Promise.all([load(), loadOverview()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}
</script>

<template>
  <div class="todo-plugin">
    <YdPageHeader
      title="我的清单"
      description="维护自己的计划任务：拆分二级小任务、设置截止时间与优先级，完成后勾选即可归档"
    >
      <div class="todo-plugin__header-actions">
        <YdButton variant="outline" @click="openTemplateDialog">
          <YdIcon name="i-ri:file-copy-line" />
          从已有任务添加
        </YdButton>
        <YdButton @click="openCreate">
          <YdIcon name="i-ri:add-line" />
          新建待办
        </YdButton>
      </div>
    </YdPageHeader>

    <YdPageMain>
      <!-- 统计卡即快捷筛选：与状态选择框共用同一筛选范围，点卡片即切换、再点恢复全部。 -->
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
          <strong>{{ overview.total }}</strong>
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
          <strong>{{ overview.pending }}</strong>
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
          <strong>{{ overview.overdue }}</strong>
        </YdAction>
        <YdAction
          type="button"
          class="todo-stat is-done"
          :class="{ 'is-active': quickRange === 'done' }"
          :aria-pressed="quickRange === 'done'"
          :title="`已完成 ${overview.done} 条，完成率 ${overview.completionRate}%`"
          @click="applyQuickFilter('done')"
        >
          <span class="todo-stat__head">
            <i class="todo-stat__dot is-success" aria-hidden="true" />
            <YdIcon name="i-ri:checkbox-circle-line" class="todo-stat__icon" />
            已完成
          </span>
          <strong>{{ overview.done }}</strong>
          <YdProgress
            class="todo-stat__progress"
            :model-value="overview.completionRate"
            aria-label="完成率进度"
          />
        </YdAction>
        <YdAction
          type="button"
          class="todo-stat"
          :class="{ 'is-active': quickRange === 'archived' }"
          :aria-pressed="quickRange === 'archived'"
          :title="`已归档 ${overview.archived} 条，归档任务仅在「已归档」筛选下可见`"
          @click="applyQuickFilter('archived')"
        >
          <span class="todo-stat__head">
            <i class="todo-stat__dot is-archived" aria-hidden="true" />
            <YdIcon name="i-ri:archive-line" class="todo-stat__icon" />
            已归档
          </span>
          <strong>{{ overview.archived }}</strong>
        </YdAction>
      </div>

      <YdLoadingOverlay :loading="loading">
        <div class="todo-plugin__table-scroll">
        <YdTable
          :columns="columns"
          :data="tableRows"
          :row-class="rowClass"
          row-key="id"
          table-root-class="todo-plugin__table-scroll"
          table-class="min-w-[960px]"
          border
          stripe
          tree
          column-visibility
          empty-text="还没有待办，点击右上角新建第一条计划任务"
        >
          <!-- 筛选行放进表格工具栏：列设置（列筛选）按钮由 YdTable 渲染在同一行末尾，
               这样「查询」与「列设置」并排，不再各占一行。 -->
          <template #toolbar>
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
              <div class="todo-plugin__filter-actions">
                <YdButton
                  variant="outline"
                  :disabled="quickRange === 'all' && !filters.keyword && !filters.priority"
                  @click="filters.keyword = ''; filters.status = ''; filters.priority = ''; filters.overdue = false; filters.archived = false; applyFilters()"
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

          <template #cell-title="{ row }">
            <div class="todo-title-cell">
              <YdAction
                type="button"
                class="todo-check"
                :class="{ 'is-done': row.original.status === 'done' }"
                :aria-label="row.original.subTaskParentId
                  ? (row.original.status === 'done' ? '把小任务标记为未完成' : '把小任务标记为已完成')
                  : (row.original.status === 'done' ? '标记为未完成' : '标记为已完成')"
                :disabled="togglingId === row.original.id || row.original.archived"
                @click="toggleDone(row.original)"
              >
                <YdIcon v-if="row.original.status === 'done'" name="i-ri:check-line" />
              </YdAction>
              <div class="todo-title-cell__body">
                <div class="todo-title-cell__text">
                  <span class="todo-title-cell__row">
                    <span class="todo-title-cell__title" :title="row.original.title">{{ row.original.title }}</span>
                    <YdTooltip v-if="!row.original.subTaskParentId && row.original.subTaskTotal > 0" text="点行首箭头展开小任务，子行可单独勾选完成">
                      <span class="todo-title-cell__subcount">
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
            </div>
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
            <YdTag
              v-if="!row.original.subTaskParentId"
              :variant="row.original.source === 'admin' ? 'default' : 'secondary'"
              :icon="row.original.source === 'admin' ? 'i-ri:send-plane-line' : 'i-ri:user-3-line'"
            >
              {{ sourceLabel(row.original) }}
            </YdTag>
            <span v-else class="todo-subtask-follow">跟随任务</span>
          </template>

          <template #cell-updatedAtLabel="{ row }">
            <template v-if="!row.original.subTaskParentId">{{ row.original.updatedAtLabel }}</template>
            <template v-else>{{ row.original.updatedAtLabel }}</template>
          </template>

          <template #cell-assignees="{ row }">
            <div class="todo-assignees">
              <span
                v-for="pid in (row.original.participantIds ?? []).slice(0, 3)"
                :key="pid"
                class="todo-assignee-chip"
              >{{ directory.labels([pid])[0] }}</span>
              <YdTooltip v-if="(row.original.participantIds ?? []).length > 3" :text="allAssignees(row.original)">
                <span class="todo-assignee-chip is-more">+{{ (row.original.participantIds ?? []).length - 3 }}</span>
              </YdTooltip>
              <span v-if="!(row.original.participantIds ?? []).length" class="todo-assignees__empty">
                {{ row.original.subTaskParentId ? '未指定' : '仅自己' }}
              </span>
            </div>
          </template>

          <template #cell-operation="{ row }">
            <div v-if="row.original.subTaskParentId && !row.original.archived" class="todo-plugin__row-actions">
              <YdButton
                v-if="isSubTaskAssignee(row.original)"
                size="sm"
                variant="outline"
                :disabled="joiningSubTaskId === row.original.id"
                @click="confirmSubTaskLeave(row.original)"
              >
                退出
              </YdButton>
              <YdButton
                v-else
                size="sm"
                variant="outline"
                :loading="joiningSubTaskId === row.original.id"
                @click="joinSubTask(row.original)"
              >
                加入
              </YdButton>
            </div>
            <div v-else-if="!row.original.subTaskParentId && row.original.owned" class="todo-plugin__row-actions">
              <YdButton v-if="!row.original.archived" size="sm" variant="outline" @click="openEdit(row.original)">
                编辑
              </YdButton>
              <YdButton
                v-if="row.original.status === 'done'"
                size="sm"
                variant="outline"
                @click="toggleArchive(row.original)"
              >
                {{ row.original.archived ? '取消归档' : '归档' }}
              </YdButton>
              <YdButton v-if="!row.original.archived" size="sm" variant="destructive" @click="confirmDelete(row.original)">
                删除
              </YdButton>
            </div>
            <div v-else-if="!row.original.archived" class="todo-plugin__row-actions">
              <YdButton size="sm" variant="outline" @click="confirmLeave(row.original)">
                退出
              </YdButton>
            </div>
          </template>
        </YdTable>
        </div>
      </YdLoadingOverlay>

      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </YdPageMain>

    <TaskFormModal v-model:open="editorOpen" :sdk="sdk" :task="editingTask" surface="user" @saved="onSaved" />

    <YdModal v-model="templateOpen" title="从已有任务添加" width="520px" :footer="false">
      <div class="todo-plugin__form">
        <div class="todo-plugin__field">
          <span>选择任务</span>
          <TaskTemplatePicker v-model="templateId" :sdk="sdk" surface="user" />
          <small class="todo-plugin__field-hint">加入后你将作为参与人看到并推进这条任务，无需复制一份新任务。</small>
        </div>
        <div class="todo-plugin__row-actions" style="justify-content: flex-end">
          <YdButton variant="outline" :disabled="templateSubmitting" @click="templateOpen = false">
            取消
          </YdButton>
          <YdButton :loading="templateSubmitting" :disabled="!templateId" @click="submitFromTemplate">
            添加到我的清单
          </YdButton>
        </div>
      </div>
    </YdModal>
  </div>
</template>

<style scoped>
.todo-plugin__header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.todo-title-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.todo-title-cell__body {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.todo-check {
  display: grid;
  place-items: center;
  flex: none;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  padding: 0;
  border: 1.5px solid var(--color-border-3, #c9cdd4);
  border-radius: 6px;
  background: transparent;
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease, transform 0.16s ease;
}

.todo-check:hover:not(:disabled) {
  border-color: rgb(var(--success-6));
  transform: scale(1.1);
}

.todo-check.is-done {
  border-color: rgb(var(--success-6));
  background: rgb(var(--success-6));
}

.todo-check:disabled {
  opacity: 0.6;
  cursor: progress;
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

.todo-title-cell__remark {
  overflow: hidden;
  color: var(--color-text-3, #86909c);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-title-cell__subcount {
  flex: none;
  color: var(--color-text-3, #86909c);
  font-size: 11px;
  white-space: nowrap;
  cursor: help;
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
  max-width: 96px;
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
  font-size: 12px;
}
</style>
