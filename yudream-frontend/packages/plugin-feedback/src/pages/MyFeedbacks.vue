<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback } from '../types'
import { YdLoadingOverlay, YdAction, YdButton, YdDropdown, YdIcon, YdInput, YdPageHeader, YdPageMain, YdPagination, YdSelect, YdTable, YdTooltip, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createFeedbackUserApi } from '../api/feedback-api'
import FeedbackSeverityTag from '../components/FeedbackSeverityTag.vue'
import FeedbackStatusTag from '../components/FeedbackStatusTag.vue'
import FeedbackTypeTag from '../components/FeedbackTypeTag.vue'
import { useColumnMenu } from '../composables/use-column-menu'
import { STATUS_OPTIONS, TYPE_OPTIONS, assigneeNames, errorMessage } from '../composables/utils'

/** 我的反馈：只服务当前登录者，服务端已把数据范围收敛到本人，前端不做任何越界筛选。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createFeedbackUserApi(props.sdk)
const router = useRouter()
const toast = useYdToast()
const confirm = useYdModal()

/** 列筛选下拉：菜单项按 YdTable 插槽给出的 table 实例生成，做法见 `useColumnMenu`。 */
const { columnMenuItems } = useColumnMenu()

const loading = ref(false)
const rows = ref<Feedback[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
// 「未完结」不是后端的 status 枚举，而是「只看未完结」的独立开关，与状态选择框互斥。
const filters = reactive({ keyword: '', status: '', type: '', openOnly: false })
const stats = reactive({ total: 0, pending: 0, processing: 0, open: 0, resolved: 0, archived: 0 })

const columns: TableColumn<Feedback>[] = [
  { id: 'title', header: '反馈内容', minWidth: 260 },
  { id: 'type', header: '类型', width: 110 },
  { id: 'severity', header: '严重程度', width: 100 },
  { id: 'status', header: '处理状态', width: 110 },
  { id: 'assignee', header: '处理人', width: 140 },
  { accessorKey: 'commentCount', header: '往来', width: 90 },
  { accessorKey: 'updatedAtLabel', header: '最近更新', width: 150 },
  { id: 'operation', header: '操作', width: 160, fixed: 'right' },
]

/** 统计卡定义：键与筛选范围一一对应，点击卡片即把列表切到该范围。 */
type QuickRange = 'all' | 'open' | 'pending' | 'processing' | 'resolved' | 'archived'

const statCards: Array<{ key: QuickRange, label: string, icon: string, tone: string }> = [
  { key: 'all', label: '全部反馈', icon: 'i-ri:inbox-line', tone: 'is-neutral' },
  { key: 'open', label: '处理中合计', icon: 'i-ri:loader-line', tone: 'is-primary' },
  { key: 'pending', label: '待处理', icon: 'i-ri:timer-line', tone: 'is-warning' },
  { key: 'processing', label: '跟进中', icon: 'i-ri:progress-2-line', tone: 'is-primary' },
  { key: 'resolved', label: '已解决', icon: 'i-ri:checkbox-circle-line', tone: 'is-success' },
  { key: 'archived', label: '已归档', icon: 'i-ri:archive-line', tone: 'is-neutral' },
]

/** 统计卡当前激活范围：与状态选择框、未完结开关共用同一套筛选语义。 */
const quickRange = computed<QuickRange>(() => {
  if (filters.openOnly) {
    return 'open'
  }
  return (filters.status || 'all') as QuickRange
})

function quickCount(range: QuickRange): number {
  if (range === 'open') {
    return stats.open
  }
  if (range === 'all') {
    return stats.total
  }
  return stats[range]
}

/** 统计卡提示：几处口径不是字面意思，鼠标停一下就能看明白。 */
function cardTitle(key: QuickRange): string | undefined {
  return key === 'all' ? '不含已归档；归档见「已归档」卡' : undefined
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
    const data = await api.stats()
    stats.total = data.total
    stats.pending = data.pending
    stats.processing = data.processing
    stats.open = data.open
    stats.resolved = data.resolved
    stats.archived = data.archived
  }
  catch {
    // 统计为辅助信息，失败时保留上一次数值，不阻断列表
  }
}

async function load() {
  loading.value = true
  try {
    const data = await api.list({
      keyword: filters.keyword,
      status: filters.status,
      type: filters.type,
      open: filters.openOnly ? 'true' : '',
      page: pager.page,
      size: pager.size,
    })
    rows.value = data.records ?? []
    pager.total = data.total ?? 0
    // 删除/撤回后可能落在空页，回退到最后一个有效页，避免用户看到空表却还有数据。
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

function applyQuickFilter(range: QuickRange) {
  const target = quickRange.value === range ? 'all' : range
  filters.openOnly = target === 'open'
  filters.status = target === 'open' || target === 'all' ? '' : target
  applyFilters()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.type = ''
  filters.openOnly = false
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
  void router.push(`/platform/plugins/feedback/me/feedbacks/${row.id}`)
}

function createFeedback() {
  void router.push('/platform/plugins/feedback/me/feedbacks/new')
}
/** 列表里的快捷归档：管理员给出结论后才出现，归档是终态，先二次确认。 */
function confirmArchive(row: Feedback) {
  confirm.confirm({
    title: '归档反馈',
    content: `确认归档「${row.title}」吗？归档后不能再修改内容，但仍可追加说明。`,
    onConfirm: async () => {
      try {
        await api.archive(row.id)
        toast.success('反馈已归档')
        await Promise.all([load(), loadStats()])
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '归档失败'))
      }
    },
  })
}
</script>

<template>
  <div class="fb-page">
    <YdPageHeader title="我的反馈" description="跟踪自己提交的问题与建议，查看处理进度并补充说明">
      <YdButton @click="createFeedback">
        <YdIcon name="i-ri:add-line" />
        提交反馈
      </YdButton>
    </YdPageHeader>

    <YdPageMain>
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
          <strong>{{ quickCount(card.key) }}</strong>
        </YdAction>
      </div>

      <YdLoadingOverlay :loading="loading">
        <div class="fb-table-block">
        <YdTable
          :columns="columns"
          :data="rows"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1040px]"
          border
          stripe
          empty-text="还没有反馈，点右上角「提交反馈」写下你的第一个问题"
        >
          <template #toolbar="{ table }">
            <div class="fb-filters">
              <YdInput
                v-model="filters.keyword"
                class="fb-filters__keyword"
                placeholder="搜索标题或描述，输入后自动搜索"
                clearable
                @keydown.enter="searchNow"
              />
              <YdSelect
                v-model="filters.status"
                class="fb-filters__select"
                :options="STATUS_OPTIONS"
                :disabled="filters.openOnly"
                @change="applyFilters"
              />
              <YdSelect
                v-model="filters.type"
                class="fb-filters__select"
                :options="TYPE_OPTIONS"
                @change="applyFilters"
              />
              <div class="fb-filters__actions">
                <YdButton variant="outline" @click="resetFilters">
                  重置
                </YdButton>
                <YdButton @click="applyFilters">
                  <YdIcon name="i-ri:search-line" />
                  查询
                </YdButton>
                <YdDropdown :items="[columnMenuItems(table)]" align="end">
                  <YdButton variant="outline" size="icon" title="列筛选" aria-label="列筛选">
                    <YdIcon name="i-lucide:columns-3" />
                  </YdButton>
                </YdDropdown>
              </div>
            </div>
          </template>

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

          <template #cell-assignee="{ row }">
            <span v-if="row.original.assignees.length" class="fb-person" :title="assigneeNames(row.original.assignees)">
              {{ assigneeNames(row.original.assignees) }}
            </span>
            <span v-else class="fb-person is-empty">待指派</span>
          </template>

          <template #cell-commentCount="{ row }">
            <YdTooltip :text="row.original.commentCount > 0 ? '含官方回复与你的补充说明' : '暂无往来记录'">
              <span class="fb-count">{{ row.original.commentCount }} 条</span>
            </YdTooltip>
          </template>

          <template #cell-operation="{ row }">
            <div class="fb-row-actions">
              <YdButton size="sm" variant="outline" @click="openDetail(row.original)">
                查看
              </YdButton>
              <YdButton
                v-if="row.original.canArchive"
                size="sm"
                @click="confirmArchive(row.original)"
              >
                归档
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
  </div>
</template>

<style scoped>
/* 统计卡即快捷筛选：与筛选行共用同一语义，点卡片即切换范围。 */
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

.fb-table-block {
  margin-top: var(--fb-sp-4);
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
  color: var(--fb-text-4);
}

.fb-count {
  color: var(--fb-text-2);
  font-size: 12px;
}

.fb-row-actions {
  display: flex;
  gap: var(--fb-sp-2);
}
</style>
