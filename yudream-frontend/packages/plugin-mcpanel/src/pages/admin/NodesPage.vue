<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { McpNode, McpNodeHistorySample } from '../../types'
import { YdAction, YdButton, YdButtonGroup, YdIcon, YdInput, YdPagination, YdProgress, YdSelect, YdTablePageLayout, YdTag, YdTooltip, useYdModal, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import NodeFormModal from '../../components/NodeFormModal.vue'
import NodeStatusTag from '../../components/NodeStatusTag.vue'
import MetricTrendChart from '../../components/MetricTrendChart.vue'
import { errorMessage, formatDateTime, formatMib, formatPercent, relativeSeenText, toNumberOr } from '../../composables/utils'
import { metricFreshness, type MetricFreshness } from '../../composables/adminListView'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'

/**
 * 节点管理：YdTablePageLayout 标准布局，表格 / 卡片双视图共用筛选与静默轮询。
 * - 轮询静默进行：不触发 v-loading，失败不清空已有数据，仅更新行内刷新状态；
 * - 请求带代际 + 筛选快照，迟到的旧响应不会覆盖新筛选下的列表；
 * - 重连按记录 busy，只禁用当前行，不锁定整页；
 * - 节点离线或快照超龄时，CPU/内存明确标注“最后快照”，不冒充实时。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const toast = useYdToast()
const modal = useYdModal()
const router = useRouter()
const route = useRoute()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const loading = ref(false)
const rows = ref<McpNode[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', status: 'all' })
/** 静默刷新状态：失败保留上次数据并在此提示（非 toast）。 */
const refreshError = ref('')
const lastRefreshedAt = ref(0)

/** 重连按节点 busy：仅禁用对应行按钮。 */
const reconnectBusy = reactive<Record<string, boolean>>({})

/** 视图模式：表格（默认）/ 卡片；卡片带实时资源趋势。 */
const VIEW_KEY = 'mcp-nodes-view'
const viewMode = ref<'table' | 'card'>(localStorage.getItem(VIEW_KEY) === 'card' ? 'card' : 'table')

watch(viewMode, (mode) => {
  try {
    localStorage.setItem(VIEW_KEY, mode)
  }
  catch {
    // ignore
  }
})

const STATUS_OPTIONS = [
  { label: '全部状态', value: 'all' },
  { label: '在线', value: 'online' },
  { label: '离线', value: 'offline' },
  { label: '连接中', value: 'connecting' },
  { label: '注册中', value: 'enrolling' },
]

const columns: TableColumn<McpNode>[] = [
  { accessorKey: 'name', header: '节点', minWidth: 200, fixed: 'left' },
  { id: 'status', header: '状态', width: 110, align: 'center' },
  { id: 'cpu', header: 'CPU', minWidth: 140 },
  { id: 'memory', header: '内存', minWidth: 200 },
  { id: 'containers', header: '容器', width: 90, align: 'center' },
  { id: 'versions', header: 'Agent / Docker', width: 140 },
  { id: 'lastSeen', header: '心跳', width: 120 },
]

const formOpen = ref(false)
const editingNode = ref<McpNode | null>(null)

function cpuPercentOf(node: McpNode): number {
  return Math.min(100, Math.max(0, Math.round(toNumberOr(node.stats?.cpuPercent))))
}

function memPercentOf(node: McpNode): number {
  const used = toNumberOr(node.stats?.memUsedMb)
  const total = toNumberOr(node.stats?.memTotalMb)
  if (!total) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((used / total) * 100)))
}

/** 指标新鲜度：offline / 快照超龄时不得渲染成实时读数。 */
function freshnessOf(node: McpNode): MetricFreshness {
  return metricFreshness({
    status: node.status,
    reportedAt: node.stats?.reportedAt ?? node.lastSeenAt,
  })
}

/** 表格/卡片通用的数值文本：非实时时显式标注快照或降级为 -。 */
function metricTextOf(node: McpNode, percent: number): string {
  const fresh = freshnessOf(node)
  if (fresh === 'offline') {
    return '-'
  }
  const text = formatPercent(percent)
  return fresh === 'stale' ? `${text}（快照）` : text
}

/**
 * 曲线 = 后台采集的整机历史（node.history，30s 采样并已落库） + 本页会话的实时点。
 * 历史由后端常驻采集，因此打开页面即有曲线、刷新/重启面板也不断档；
 * 本页只在轮询到比历史更新鲜的快照时追加实时尾点（liveSamples 带时间戳去重）。
 */
const cpuSeries = reactive<Record<string, number[]>>({})
const memSeries = reactive<Record<string, number[]>>({})
const liveSamples = reactive<Record<string, Array<{ at: number, cpu: number, mem: number }>>>({})
const SERIES_LIMIT = 90

function clampPercent(value: number): number {
  return Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : 0
}

function memoryPercentOfSample(sample: McpNodeHistorySample): number {
  const total = toNumberOr(sample.memTotalMb)
  if (!total) {
    return 0
  }
  return clampPercent((toNumberOr(sample.memUsedMb) / total) * 100)
}

function recordSamples() {
  const now = Date.now()
  rows.value.forEach((node) => {
    if (node.stats) {
      const bucket = liveSamples[node.id] ?? []
      bucket.push({ at: now, cpu: cpuPercentOf(node), mem: memPercentOf(node) })
      liveSamples[node.id] = bucket.length > SERIES_LIMIT ? bucket.slice(bucket.length - SERIES_LIMIT) : bucket
    }
    const history = Array.isArray(node.history) ? node.history : []
    const lastHistoryAt = history.length ? toNumberOr(history[history.length - 1].at) : 0
    const live = (liveSamples[node.id] ?? []).filter(sample => sample.at > lastHistoryAt)
    const cpu = [
      ...history.map(sample => clampPercent(toNumberOr(sample.cpuPercent))),
      ...live.map(sample => sample.cpu),
    ]
    const mem = [
      ...history.map(memoryPercentOfSample),
      ...live.map(sample => sample.mem),
    ]
    cpuSeries[node.id] = cpu.slice(-SERIES_LIMIT)
    memSeries[node.id] = mem.slice(-SERIES_LIMIT)
  })
}

/** 请求代际：响应仅在其仍是最新请求且筛选未变化时生效。 */
let loadSeq = 0

async function load(options: { silent?: boolean } = {}) {
  const silent = options.silent ?? false
  const seq = ++loadSeq
  const request = {
    page: pager.page,
    size: pager.size,
    keyword: filters.keyword.trim(),
    status: filters.status,
  }
  if (!silent) {
    loading.value = true
  }
  try {
    const page = await api.pageNodes(
      request.page,
      request.size,
      request.keyword || undefined,
      request.status === 'all' ? undefined : request.status,
    )
    const outdated = seq !== loadSeq
      || request.keyword !== filters.keyword.trim()
      || request.status !== filters.status
      || request.page !== pager.page
      || request.size !== pager.size
    if (outdated) {
      return
    }
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
    recordSamples()
    refreshError.value = ''
    lastRefreshedAt.value = Date.now()
  }
  catch (error) {
    if (seq !== loadSeq) {
      return
    }
    if (silent) {
      // 静默轮询失败：保留已有数据，行内提示；下一次轮询成功即恢复
      refreshError.value = errorMessage(error, '刷新失败')
    }
    else {
      refreshError.value = ''
      toast.error(errorMessage(error, '加载节点列表失败'))
    }
  }
  finally {
    if (seq === loadSeq && !silent) {
      loading.value = false
    }
  }
}

function search() {
  pager.page = 1
  void load()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = 'all'
  search()
}

function detailPath(id: string) {
  const base = route.path.replace(/\/+$/, '')
  return `${base}/${encodeURIComponent(id)}`
}

function openDetail(node: McpNode) {
  void router.push(detailPath(node.id))
}

function openCreate() {
  editingNode.value = null
  formOpen.value = true
}

function openEdit(node: McpNode) {
  editingNode.value = node
  formOpen.value = true
}

function onCreated(node: McpNode) {
  toast.success('节点已创建，请在详情页签发一次性注册凭据')
  void router.push(detailPath(node.id))
}

async function reconnect(node: McpNode) {
  if (reconnectBusy[node.id]) {
    return
  }
  reconnectBusy[node.id] = true
  try {
    await api.reconnectNode(node.id)
    toast.success(`已触发节点「${node.name}」重连`)
    void load({ silent: true })
  }
  catch (error) {
    toast.error(errorMessage(error, '重连失败'))
  }
  finally {
    reconnectBusy[node.id] = false
  }
}

function confirmDelete(node: McpNode) {
  modal.confirm({
    title: '删除节点',
    content: `确认删除节点「${node.name}」吗？节点上的实例与端口分配将不可用，此操作不可恢复。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        const result = await api.removeNode(node.id)
        if (result && result.deleted === false) {
          toast.warning('后端未确认删除，请稍后重试')
        }
        else {
          toast.success(`节点「${node.name}」已删除`)
        }
        if (rows.value.length === 1 && pager.page > 1) {
          pager.page -= 1
        }
        void load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除节点失败'))
      }
    },
  })
}

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void load()
  // 静默轮询节点快照（agents 每 5s 上报）：驱动卡片 CPU/内存与趋势采样，
  // 不触发 v-loading；页面隐藏时暂停。
  pollTimer = setInterval(() => {
    if (document.hidden) {
      return
    }
    void load({ silent: true })
  }, 10_000)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

/** 节点行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '详情', icon: 'morph:eye', onClick: row => openDetail(row as never) },
  { label: '编辑', icon: 'morph:pencil', visible: () => Boolean(canManage.value ?? canManage), onClick: row => openEdit(row as never) },
  { label: '重连', icon: 'morph:refresh', visible: () => Boolean(canManage.value ?? canManage), disabled: row => Boolean(reconnectBusy[(row as { id: string }).id]), onClick: row => reconnect(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="节点管理">
    <template #actions>
      <YdButtonGroup class="mr-1">
        <YdTooltip text="表格视图">
          <YdButton size="sm" :variant="viewMode === 'table' ? 'default' : 'outline'" @click="viewMode = 'table'">
            <YdIcon name="i-ri:table-line" />
          </YdButton>
        </YdTooltip>
        <YdTooltip text="卡片视图（实时监控）">
          <YdButton size="sm" :variant="viewMode === 'card' ? 'default' : 'outline'" @click="viewMode = 'card'">
            <YdIcon name="i-ri:layout-grid-line" />
          </YdButton>
        </YdTooltip>
      </YdButtonGroup>
      <YdButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/node-deploy')">
        <YdIcon name="i-ri:book-open-line" />
        部署指南
      </YdButton>
      <YdButton v-if="canManage" @click="openCreate">
        <YdIcon name="i-ri:add-line" />
        新增节点
      </YdButton>
    </template>
    <template #search>
      <div class="flex flex-wrap items-center gap-2">
        <div class="yd-filter-grid" style="--yd-filter-columns: 2">
          <YdInput v-model="filters.keyword" clearable placeholder="节点名称 / 地址" class="w-full" @keydown.enter="search" @clear="search" />
          <YdSelect v-model="filters.status" :options="STATUS_OPTIONS" class="w-full" @change="search" />
          <div class="flex items-center justify-end gap-2">
            <YdButton variant="outline" @click="resetFilters">
              重置
            </YdButton>
            <YdButton @click="search">
              <YdIcon name="i-ri:search-line" />
              查询
            </YdButton>
          </div>
        </div>
      </div>
    </template>
    <template #table>
    <!-- 卡片视图：节点监控卡 -->
    <YdLoadingOverlay v-if="viewMode === 'card'" :loading="loading"><div>
      <div v-if="!rows.length" class="mcp-empty-hint">
        暂无节点，点击右上角「新增节点」接入第一台运行 Docker Agent 的机器
      </div>
      <div v-else class="mcp-card-grid">
        <div v-for="node in rows" :key="node.id" class="mcp-card">
          <div class="mcp-card-head">
            <YdAction type="button" class="mcp-card-title" :title="node.name" @click="openDetail(node)">
              {{ node.name }}
            </YdAction>
            <span class="mcp-spacer" />
            <NodeStatusTag :status="node.status" />
            <YdTag v-if="node.enabled === false" variant="secondary">
              已停用
            </YdTag>
          </div>
          <div class="mcp-card-sub" :title="node.endpoint">
            {{ node.endpoint || '未配置控制信道' }}
          </div>
          <div class="mcp-card-meta">
            <span>Agent {{ node.agentVersion || '-' }}</span>
            <span>Docker {{ node.stats?.dockerVersion || node.dockerVersion || '-' }}</span>
            <span>容器 {{ node.stats?.containers?.length ?? 0 }}</span>
            <span :title="formatDateTime(node.lastSeenAt)">{{ relativeSeenText(node.lastSeenAt) }}</span>
          </div>
          <div class="mcp-card-metrics">
            <div class="mcp-metric">
              <div class="mcp-metric-head" :title="freshnessOf(node) === 'stale' ? `最后快照 ${relativeSeenText(node.stats?.reportedAt ?? node.lastSeenAt)}` : undefined">
                <span>CPU</span>
                <strong>{{ node.stats ? metricTextOf(node, cpuPercentOf(node)) : '-' }}</strong>
              </div>
              <MetricTrendChart :values="cpuSeries[node.id] ?? []" :height="30" :max="100" name="CPU" />
            </div>
            <div class="mcp-metric">
              <div class="mcp-metric-head">
                <span>内存 {{ node.stats ? `${formatMib(node.stats.memUsedMb)} / ${formatMib(node.stats.memTotalMb)}` : '' }}</span>
                <strong>{{ node.stats ? metricTextOf(node, memPercentOf(node)) : '-' }}</strong>
              </div>
              <MetricTrendChart :values="memSeries[node.id] ?? []" :height="30" :max="100" name="内存" />
            </div>
          </div>
          <div class="mcp-card-ops">
            <YdTooltip text="详情">
              <YdButton size="icon-sm" variant="ghost" @click="openDetail(node)">
                <YdIcon name="i-ri:eye-line" />
              </YdButton>
            </YdTooltip>
            <YdTooltip v-if="canManage" text="编辑">
              <YdButton size="icon-sm" variant="ghost" @click="openEdit(node)">
                <YdIcon name="i-ri:edit-line" />
              </YdButton>
            </YdTooltip>
            <YdTooltip v-if="canManage" text="重连">
              <YdButton size="icon-sm" variant="ghost" :disabled="reconnectBusy[node.id]" @click="reconnect(node)">
                <YdIcon name="i-ri:refresh-line" />
              </YdButton>
            </YdTooltip>
            <YdTooltip v-if="canDelete" text="删除">
              <YdButton size="icon-sm" variant="ghost" class="text-destructive" @click="confirmDelete(node)">
                <YdIcon name="i-ri:delete-bin-line" />
              </YdButton>
            </YdTooltip>
          </div>
        </div>
      </div>
    </div></YdLoadingOverlay>

    <!-- 表格视图：标准 YdTable 管理页 -->
    <template v-else>
      <YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
        :columns="columns"
        :data="rows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[1080px]"
        border
        stripe
        column-visibility
        empty-text="暂无节点，点击右上角「新增节点」接入第一台运行 Docker Agent 的机器"
      >
        <template #cell-name="{ row }">
          <div class="flex min-w-0 flex-col">
            <YdAction type="button" class="truncate font-medium text-primary hover:underline" @click="openDetail(row.original)">
              {{ row.original.name }}
            </YdAction>
            <span class="truncate text-xs text-muted-foreground" :title="row.original.endpoint">
              {{ row.original.endpoint || '未配置控制信道' }}
            </span>
          </div>
        </template>
        <template #cell-status="{ row }">
          <div class="flex flex-wrap items-center justify-center gap-1">
            <NodeStatusTag :status="row.original.status" />
            <YdTag v-if="row.original.enabled === false" variant="secondary">
              已停用
            </YdTag>
          </div>
        </template>
        <template #cell-cpu="{ row }">
          <div v-if="row.original.stats && freshnessOf(row.original) !== 'offline'" class="flex items-center gap-2">
            <YdProgress :model-value="cpuPercentOf(row.original)" class="min-w-0 flex-1" />
            <span
              class="w-14 shrink-0 text-right text-xs text-muted-foreground"
              :title="freshnessOf(row.original) === 'stale' ? `最后快照 ${relativeSeenText(row.original.stats?.reportedAt ?? row.original.lastSeenAt)}` : undefined"
            >
              {{ metricTextOf(row.original, cpuPercentOf(row.original)) }}
            </span>
          </div>
          <span v-else class="text-muted-foreground">-</span>
        </template>
        <template #cell-memory="{ row }">
          <div v-if="row.original.stats && freshnessOf(row.original) !== 'offline'" class="flex items-center gap-2">
            <YdProgress :model-value="memPercentOf(row.original)" class="min-w-0 flex-1" />
            <span class="shrink-0 text-right text-xs text-muted-foreground">
              {{ metricTextOf(row.original, memPercentOf(row.original)) }} · {{ formatMib(row.original.stats.memUsedMb) }}/{{ formatMib(row.original.stats.memTotalMb) }}
            </span>
          </div>
          <span v-else class="text-muted-foreground">-</span>
        </template>
        <template #cell-containers="{ row }">
          {{ row.original.stats?.containers?.length ?? '-' }}
        </template>
        <template #cell-versions="{ row }">
          <div class="flex flex-col text-xs">
            <span>Agent {{ row.original.agentVersion || '-' }}</span>
            <span class="text-muted-foreground">Docker {{ row.original.stats?.dockerVersion || row.original.dockerVersion || '-' }}</span>
          </div>
        </template>
        <template #cell-lastSeen="{ row }">
          <span :title="formatDateTime(row.original.lastSeenAt)">{{ relativeSeenText(row.original.lastSeenAt) }}</span>
        </template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    </template>
    <template #footer>
      <!-- 静默轮询状态与分页同排：失败保留上次数据并在此提示（不弹 toast） -->
      <div class="mcp-row">
        <span v-if="refreshError" class="mcp-refresh-meta is-error" title="保留上次加载的数据，可点击查询重试">
          刷新失败，展示上次数据 · {{ refreshError }}
        </span>
        <span v-else-if="lastRefreshedAt" class="mcp-refresh-meta">
          更新于 {{ formatDateTime(lastRefreshedAt) }} · 10s 自动刷新
        </span>
        <YdPagination
          v-model:page="pager.page"
          v-model:size="pager.size"
          :total="pager.total"
          class="mcp-spacer"
          @page-change="() => load()"
          @size-change="() => { pager.page = 1; load() }"
        />
      </div>
    </template>
  </YdTablePageLayout>

  <NodeFormModal
    v-model:open="formOpen"
    :api="api"
    :node="editingNode"
    @created="onCreated"
    @saved="() => { toast.success('节点已保存'); load() }"
  />
</template>
