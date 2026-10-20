<script setup lang="ts">
import type { ActionMenuItem, EntityPeekFact, StatusTone, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback } from '../types'
import { YdEntityPeek, YdLoadingOverlay, YdAction, YdButton, YdIcon, YdInput, YdPagination, YdRangePicker, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTablePageLayout, YdTooltip, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createFeedbackAdminApi } from '../api/feedback-api'
import FeedbackSeverityTag from '../components/FeedbackSeverityTag.vue'
import FeedbackStatusTag from '../components/FeedbackStatusTag.vue'
import FeedbackTypeTag from '../components/FeedbackTypeTag.vue'
import UserSelect from '../components/UserSelect.vue'
import { SEVERITY_OPTIONS, STATUS_OPTIONS, TYPE_OPTIONS, assigneeLabel, assigneeNames, errorMessage, parseDayRange, personLabel } from '../composables/utils'

/**
 * 反馈管理：跨用户受理入口，按提交人、状态、类型、严重程度与处理人筛选。
 *
 * <p>跨用户查询只出现在管理端接口，权限与数据范围都独立于用户端；这里不做任何「以管理身份
 * 查看用户端数据」的复用。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const adminApi = createFeedbackAdminApi(props.sdk)
const router = useRouter()
const toast = useYdToast()
const confirm = useYdModal()

const loading = ref(false)
const rows = ref<Feedback[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({
  keyword: '',
  status: '',
  type: '',
  severity: '',
  assigneeId: '',
  authorId: '',
  unassigned: false,
})
/** 提交时间区间：YdRangePicker 只给 yyyy-MM-dd，落到查询前再转毫秒区间。 */
const submittedRange = ref<string[] | undefined>(undefined)
const stats = reactive({ total: 0, pending: 0, processing: 0, open: 0, resolved: 0, rejected: 0, archived: 0 })

/**
 * 「未完结合计」不是后端的状态枚举，而是「只看未完结」的查询参数。
 *
 * <p>把它塞进状态下拉会造成「看着像状态、实际是范围」的歧义，因此用一个显式哨兵值承载，
 * 在 statusModel 与 quickRange 两处统一翻译成 open=true。
 */
const OPEN_ONLY = '__open__'

type QuickRange = 'all' | 'pending' | 'processing' | 'open' | 'unassigned' | 'resolved' | 'rejected' | 'archived'

const statCards: Array<{ key: QuickRange, label: string, icon: string, tone: string }> = [
  { key: 'all', label: '全部反馈', icon: 'i-ri:inbox-archive-line', tone: 'is-neutral' },
  { key: 'pending', label: '待处理', icon: 'i-ri:timer-line', tone: 'is-warning' },
  { key: 'processing', label: '跟进中', icon: 'i-ri:progress-2-line', tone: 'is-primary' },
  { key: 'open', label: '未完结合计', icon: 'i-ri:loader-line', tone: 'is-primary' },
  { key: 'unassigned', label: '待指派', icon: 'i-ri:user-unfollow-line', tone: 'is-warning' },
  { key: 'resolved', label: '已解决', icon: 'i-ri:checkbox-circle-line', tone: 'is-success' },
  { key: 'rejected', label: '不予处理', icon: 'i-ri:forbid-line', tone: 'is-neutral' },
  { key: 'archived', label: '已归档', icon: 'i-ri:archive-line', tone: 'is-neutral' },
]

const columns: TableColumn<Feedback>[] = [
  { id: 'title', header: '反馈内容', minWidth: 240, fixed: 'left' },
  { id: 'type', header: '类型', width: 110 },
  { id: 'severity', header: '严重程度', width: 100 },
  { id: 'status', header: '处理状态', width: 110 },
  { id: 'author', header: '提交人', width: 130 },
  { id: 'assignee', header: '处理人', width: 170 },
  { accessorKey: 'updatedAtLabel', header: '最近更新', width: 150 },
]

/**
 * 行操作统一收进操作菜单：处理/归档是常规流转，删除是危险动作但保持既有确认流程。
 *
 * <p>归档只对未归档的反馈出现；菜单由 YdResponsiveEnhancedTable 渲染成固定右侧的唯一操作列，
 * 桌面与移动卡片共用同一组配置。
 */
const rowActions: ActionMenuItem<Feedback>[] = [
  { label: '处理', icon: 'morph:eye', onClick: row => openDetail(row) },
  { label: '归档', icon: 'i-ri:archive-line', visible: row => row.status !== 'archived', onClick: row => confirmArchive(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

/** 未完结合计与「待指派」都是范围而非状态枚举，因此在卡片层翻译成对应查询参数。 */
const quickRange = computed<QuickRange>(() => {
  if (filters.unassigned) {
    return 'unassigned'
  }
  if (filters.status === OPEN_ONLY) {
    return 'open'
  }
  return (filters.status || 'all') as QuickRange
})

function quickCount(range: QuickRange): number {
  if (range === 'all') {
    return stats.total
  }
  if (range === 'open') {
    return stats.open
  }
  if (range === 'unassigned') {
    // 统计接口没有「待指派」口径，用「未完结中未被指派的占比」无法离线推导，故只给文字提示。
    return 0
  }
  return stats[range]
}

/** 统计卡提示：几处口径不是字面意思，鼠标停一下就能看明白。 */
function cardTitle(key: QuickRange): string | undefined {
  if (key === 'unassigned') {
    return '只看没有处理人的反馈'
  }
  return key === 'all' ? '不含已归档；归档见「已归档」卡' : undefined
}

/** 状态筛选选项：在标准状态之外补一个「未完结合计」范围项（对应 openOnly 查询参数）。 */
const statusFilterOptions = [
  { label: '全部状态', value: '' },
  ...STATUS_OPTIONS.slice(1),
  { label: '未完结合计', value: OPEN_ONLY },
]

/** 状态选择框的模型：把「未完结合计」映射成独立选项，避免与具体状态混淆。 */
const statusModel = computed({
  get: () => filters.status,
  set: (value: string) => {
    filters.status = value
    filters.unassigned = false
  },
})

/**
 * 处理人 / 提交人筛选项的模型代理。
 *
 * <p>UserSelect 的模型恒为 string[]（处理人本身是集合），而筛选项是单值，
 * 这里做一次「零到一人名单」的双向转换，顺带在变更时立即查询——
 * 与同一行的 YdSelect 保持一致，避免「有的筛选器改了立刻生效、有的要再点查询」。
 */
const assigneeFilterModel = computed({
  get: () => (filters.assigneeId ? [filters.assigneeId] : []),
  set: (value: string[]) => {
    filters.assigneeId = value[0] ?? ''
    applyFilters()
  },
})

const authorFilterModel = computed({
  get: () => (filters.authorId ? [filters.authorId] : []),
  set: (value: string[]) => {
    filters.authorId = value[0] ?? ''
    applyFilters()
  },
})

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

/**
 * 「只看待指派」开关的模型代理。
 *
 * <p>YdSwitch 只暴露 v-model、没有 change 事件，若改成 watch 模型变化再查询，
 * 统计卡与重置按钮的赋值也会触发一次重复请求；这里像仓库里其他筛选器一样用 setter 承接副作用，
 * 保证「改值」与「触发查询」严格一一对应。
 */
const unassignedModel = computed({
  get: () => filters.unassigned,
  set: (value: boolean) => {
    filters.unassigned = value
    if (value) {
      filters.status = ''
    }
    applyFilters()
  },
})

onMounted(() => {
  void Promise.all([load(), loadStats()])
})

async function loadStats() {
  try {
    const data = await adminApi.stats()
    stats.total = data.total
    stats.pending = data.pending
    stats.processing = data.processing
    stats.open = data.open
    stats.resolved = data.resolved
    stats.rejected = data.rejected
    stats.archived = data.archived
  }
  catch {
    // 统计为辅助信息，失败时保留上一次数值，不阻断列表
  }
}

async function load() {
  loading.value = true
  const range = parseDayRange(submittedRange.value)
  try {
    const data = await adminApi.list({
      keyword: filters.keyword,
      status: filters.status === OPEN_ONLY ? '' : filters.status,
      type: filters.type,
      severity: filters.severity,
      assigneeId: filters.assigneeId,
      authorId: filters.authorId,
      open: filters.status === OPEN_ONLY ? 'true' : '',
      unassigned: filters.unassigned ? 'true' : '',
      from: range.from ?? '',
      to: range.to ?? '',
      page: pager.page,
      size: pager.size,
    })
    rows.value = data.records ?? []
    pager.total = data.total ?? 0
    if (rows.value.length === 0 && pager.page > 1) {
      pager.page = Math.max(1, Math.ceil(pager.total / pager.size))
      await load()
    }
  }
  catch (cause: unknown) {
    rows.value = []
    pager.total = 0
    toast.error(errorMessage(cause, '反馈列表加载失败'))
  }
  finally {
    loading.value = false
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

/** 统计卡即快捷筛选：点当前范围则回到「全部」。 */
function applyQuickFilter(range: QuickRange) {
  const target = quickRange.value === range ? 'all' : range
  filters.unassigned = target === 'unassigned'
  filters.status = target === 'unassigned' || target === 'all'
    ? ''
    : target === 'open' ? OPEN_ONLY : target
  applyFilters()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.type = ''
  filters.severity = ''
  filters.assigneeId = ''
  filters.authorId = ''
  filters.unassigned = false
  submittedRange.value = undefined
  applyFilters()
}

function onPageChange() {
  void load()
}

function onSizeChange() {
  pager.page = 1
  void load()
}

function openDetail(row: Feedback) {
  void router.push(`/platform/plugins/feedback/admin/feedbacks/${row.id}`)
}

function confirmDelete(row: Feedback) {
  confirm.confirm({
    title: '删除反馈',
    content: `确认删除「${row.title}」吗？该反馈的全部回复与处理记录会一并删除，且不可恢复；通常应改为流转状态而不是删除。`,
    onConfirm: async () => {
      try {
        await adminApi.remove(row.id)
        toast.success('反馈已删除')
        await Promise.all([load(), loadStats()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}
/**
 * 列表里的快捷归档。
 *
 * <p>与详情页把状态流转为「已归档」等价，只是省掉进出详情页两次点击；后端会把留痕记成「归档」
 * 而不是普通状态变更，时间线上更容易看出来。
 */
function confirmArchive(row: Feedback) {
  confirm.confirm({
    title: '归档反馈',
    content: `确认归档「${row.title}」吗？归档后不能再修改内容，但仍可追加说明。`,
    onConfirm: async () => {
      try {
        await adminApi.archive(row.id)
        toast.success('反馈已归档')
        await Promise.all([load(), loadStats()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '归档失败'))
      }
    },
  })
}

/** 处理状态在 peek 卡里的语义色：与 FeedbackStatusTag 的色相一一对应。 */
function statusTone(status: Feedback['status']): StatusTone {
  if (status === 'pending') {
    return 'warning'
  }
  if (status === 'processing') {
    return 'info'
  }
  if (status === 'resolved') {
    return 'success'
  }
  if (status === 'rejected') {
    return 'danger'
  }
  return 'neutral'
}

/**
 * 用户 peek 卡的本地事实清单。
 *
 * <p>列表接口没有按 ID 反查用户档案的通道，头像/邮箱拿不到，这里只回显行内已有的反馈
 * 上下文（提交时间、状态、严重程度），并把内部 ID 以等宽字体列进卡片，不虚构用户资料。
 */
function rowFacts(row: Feedback): EntityPeekFact[] {
  return [
    { label: '提交时间', value: row.createdAtLabel },
    { label: '处理状态', value: row.statusLabel, tone: statusTone(row.status) },
    { label: '严重程度', value: row.severityLabel },
  ]
}
</script>

<template>
  <YdTablePageLayout
    title="反馈管理"
    stats-title="反馈统计与快捷筛选"
    :stats-default-expanded="true"
    :search-default-folded="false"
  >
      <!-- 统计卡即快捷筛选：点当前范围则回到「全部」。 -->
      <template #stats>
        <div class="fb-stat-strip" role="group" aria-label="反馈统计与快捷筛选">
          <YdAction
            v-for="card in statCards"
            :key="card.key"
            type="button"
            class="fb-stat"
            :class="[card.tone, { 'is-active': quickRange === card.key }]"
            :aria-pressed="quickRange === card.key"
            :title="cardTitle(card.key)"
            @click="applyQuickFilter(card.key)"
          >
            <span class="fb-stat__head">
              <i class="fb-stat__dot" aria-hidden="true" />
              <YdIcon :name="card.icon" class="fb-stat__icon" />
              {{ card.label }}
            </span>
            <strong v-if="card.key !== 'unassigned'">{{ quickCount(card.key) }}</strong>
            <strong v-else class="fb-stat__toggle">{{ filters.unassigned ? '筛选中' : '点击筛选' }}</strong>
          </YdAction>
        </div>
      </template>

      <template #search>
        <div class="fb-filters">
          <YdInput
            v-model="filters.keyword"
            class="fb-filters__keyword"
            placeholder="搜索标题、描述或回复内容，输入后自动搜索"
            clearable
            @keydown.enter="searchNow"
          />
          <YdSelect
            v-model="statusModel"
            class="fb-filters__select"
            :options="statusFilterOptions"
            @change="applyFilters"
          />
          <YdSelect
            v-model="filters.type"
            class="fb-filters__select"
            :options="TYPE_OPTIONS"
            @change="applyFilters"
          />
          <YdSelect
            v-model="filters.severity"
            class="fb-filters__select"
            :options="SEVERITY_OPTIONS"
            @change="applyFilters"
          />
          <!--
            第二行：处理人及之后的筛选与动作键。用 .fb-filters__row 主动断行，
            让它们成为完整的一组，而不是被自动换行切散在两边。
          -->
          <div class="fb-filters__row">
            <UserSelect
              v-model="assigneeFilterModel"
              class="fb-filters__select"
              :sdk="sdk"
              scope="manage"
              placeholder="按处理人筛选"
              title="按处理人筛选"
            />
            <UserSelect
              v-model="authorFilterModel"
              class="fb-filters__select"
              :sdk="sdk"
              placeholder="按提交人筛选"
              title="按提交人筛选"
            />
            <YdRangePicker
              v-model="submittedRange"
              class="fb-filters__range"
              :placeholder="['提交开始日期', '提交结束日期']"
              @change="applyFilters"
            />
            <label class="fb-filters__switch">
              <YdSwitch v-model="unassignedModel" />
              <span>只看待指派</span>
            </label>
            <div class="fb-filters__actions">
              <YdButton variant="outline" @click="resetFilters">
                重置
              </YdButton>
              <YdButton @click="applyFilters">
                <YdIcon name="i-ri:search-line" />
                查询
              </YdButton>
            </div>
          </div>
        </div>
      </template>

      <template #table>
        <YdLoadingOverlay :loading="loading">
          <YdResponsiveEnhancedTable
            :columns="columns"
            :data="rows"
            row-key="id"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[1180px]"
            border
            stripe
            column-visibility
            :actions="rowActions"
            empty-text="没有符合筛选条件的反馈"
          >
            <template #cell-title="{ row }">
              <div class="fb-title-cell">
                <span class="fb-title-cell__title" :title="row.original.title">{{ row.original.title }}</span>
                <span class="fb-title-cell__desc" :title="row.original.description">{{ row.original.description }}</span>
              </div>
            </template>

            <template #cell-type="{ row }">
              <FeedbackTypeTag :type="row.original.type" :label="row.original.typeLabel" />
            </template>

            <template #cell-severity="{ row }">
              <FeedbackSeverityTag :severity="row.original.severity" :label="row.original.severityLabel" />
            </template>

            <template #cell-status="{ row }">
              <FeedbackStatusTag :status="row.original.status" :label="row.original.statusLabel" />
            </template>

            <template #cell-author="{ row }">
              <YdEntityPeek
                :title="personLabel(row.original.authorName, row.original.authorId)"
                :subtitle="row.original.authorId"
                :facts="rowFacts(row.original)"
              />
            </template>

            <template #cell-assignee="{ row }">
              <div v-if="row.original.assignees.length" class="fb-assignees">
                <YdEntityPeek
                  v-for="assignee in row.original.assignees.slice(0, 2)"
                  :key="assignee.id"
                  :title="assigneeLabel(assignee)"
                  :subtitle="assignee.id"
                  :facts="rowFacts(row.original)"
                />
                <YdTooltip
                  v-if="row.original.assignees.length > 2"
                  :text="`其余处理人：${assigneeNames(row.original.assignees.slice(2))}`"
                >
                  <span class="fb-person is-more">+{{ row.original.assignees.length - 2 }}</span>
                </YdTooltip>
              </div>
              <YdTooltip v-else text="还没有处理人，管理员更新该反馈后会自动成为处理人">
                <span class="fb-person is-empty">待指派</span>
              </YdTooltip>
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
</template>

<style scoped>
.fb-stat-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--fb-sp-3);
}

.fb-stat {
  display: grid;
  gap: var(--fb-sp-2);
  padding: var(--fb-sp-4);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.fb-stat:hover {
  border-color: var(--fb-text-4);
}

.fb-stat.is-active {
  border-color: var(--color-primary-6, #165dff);
  box-shadow: 0 0 0 2px var(--color-primary-light-1, #e8f3ff);
}

.fb-stat__head {
  display: flex;
  align-items: center;
  gap: var(--fb-sp-2);
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-stat__icon {
  font-size: 14px;
}

.fb-stat__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--fb-radius-pill);
  background: var(--fb-text-4);
}

.fb-stat.is-primary .fb-stat__dot {
  background: var(--color-primary-6, #165dff);
}

.fb-stat.is-warning .fb-stat__dot {
  background: rgb(var(--warning-6));
}

.fb-stat.is-success .fb-stat__dot {
  background: rgb(var(--success-6));
}

.fb-stat strong {
  color: var(--fb-text-1);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
}

.fb-stat__toggle {
  font-size: 13px !important;
}

.fb-title-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.fb-title-cell__title {
  overflow: hidden;
  color: var(--fb-text-1);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-title-cell__desc {
  overflow: hidden;
  color: var(--fb-text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-person {
  overflow: hidden;
  display: block;
  color: var(--fb-text-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fb-person.is-empty {
  color: rgb(var(--warning-6));
  cursor: help;
}

.fb-person.is-more {
  color: var(--fb-text-3);
  cursor: help;
}

/* 多处理人收进 peek 触发器 + 溢出徽标：允许换行，不横向撑爆固定宽列。 */
.fb-assignees {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px var(--fb-sp-1);
  min-width: 0;
}

.fb-assignees :deep(.yd-entity-peek__trigger) {
  max-width: 100%;
  padding-inline: 0;
}
</style>
