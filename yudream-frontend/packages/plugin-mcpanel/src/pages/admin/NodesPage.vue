<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { McpNode, McpNodeHistorySample } from '../../types'
import { FaButton, FaButtonGroup, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaProgress, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, FaTooltip, useFaModal, useFaToast } from '@yudream/components'
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
 * 节点管理：表格 / 卡片双视图共用一套筛选工具栏与静默轮询。
 * - 轮询静默进行：不触发 v-loading，失败不清空已有数据，仅更新行内刷新状态；
 * - 请求带代际 + 筛选快照，迟到的旧响应不会覆盖新筛选下的列表；
 * - 重连按记录 busy，只禁用当前行，不锁定整页；
 * - 节点离线或快照超龄时，CPU/内存明确标注“最后快照”，不冒充实时。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
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
  { id: 'operation', header: '操作', width: 200, align: 'center', fixed: 'right' },
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

function manualRefresh() {
  void load()
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
</script>

<template>
  <FaPageHeader title="节点管理" description="远程节点控制面：接入、状态、资源与容器概览">
    <FaButtonGroup class="mr-1">
      <FaTooltip text="表格视图">
        <FaButton size="sm" :variant="viewMode === 'table' ? 'default' : 'outline'" @click="viewMode = 'table'">
          <FaIcon name="i-ri:table-line" />
        </FaButton>
      </FaTooltip>
      <FaTooltip text="卡片视图（实时监控）">
        <FaButton size="sm" :variant="viewMode === 'card' ? 'default' : 'outline'" @click="viewMode = 'card'">
          <FaIcon name="i-ri:layout-grid-line" />
        </FaButton>
      </FaTooltip>
    </FaButtonGroup>
    <FaButton variant="outline" class="mr-1" @click="router.push('/platform/plugins/mcpanel/admin/node-deploy')">
      <FaIcon name="i-ri:book-open-line" />
      部署指南
    </FaButton>
    <FaButton v-if="canManage" @click="openCreate">
      <FaIcon name="i-ri:add-line" />
      新增节点
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <!-- 共用筛选工具栏：表格 / 卡片视图切换时保持不消失 -->
    <div class="mcp-list-toolbar">
      <FaSearchBar :show-toggle="false" class="w-full">
        <div class="mcp-filter-grid mcp-filter-grid--2">
          <FaInput
            v-model="filters.keyword"
            clearable
            placeholder="节点名称 / 地址"
            class="w-full"
            @keydown.enter="search"
            @clear="search"
          />
          <FaSelect v-model="filters.status" :options="STATUS_OPTIONS" class="w-full" @change="search" />
          <div class="mcp-filter-actions">
            <FaButton variant="outline" @click="resetFilters">
              重置
            </FaButton>
            <FaButton variant="outline" :loading="loading" @click="manualRefresh">
              <FaIcon name="i-ri:refresh-line" />
              刷新
            </FaButton>
            <FaButton @click="search">
              <FaIcon name="i-ri:search-line" />
              查询
            </FaButton>
          </div>
        </div>
      </FaSearchBar>
      <div class="mcp-row">
        <span v-if="refreshError" class="mcp-refresh-meta is-error" title="保留上次加载的数据，可点击刷新重试">
          刷新失败，展示上次数据 · {{ refreshError }}
        </span>
        <span v-else-if="lastRefreshedAt" class="mcp-refresh-meta">
          更新于 {{ formatDateTime(lastRefreshedAt) }} · 10s 自动刷新
        </span>
      </div>
    </div>

    <!-- 卡片视图：节点监控卡 -->
    <div v-if="viewMode === 'card'" class="mcp-page-gap">
      <div v-if="!rows.length" class="mcp-empty-hint">
        暂无节点，点击右上角「新增节点」接入第一台运行 Docker Agent 的机器
      </div>
      <div v-else class="mcp-card-grid">
        <div v-for="node in rows" :key="node.id" class="mcp-card">
          <div class="mcp-card-head">
            <button type="button" class="mcp-card-title" :title="node.name" @click="openDetail(node)">
              {{ node.name }}
            </button>
            <span class="mcp-spacer" />
            <NodeStatusTag :status="node.status" />
            <FaTag v-if="node.enabled === false" variant="secondary">
              已停用
            </FaTag>
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
            <FaTooltip text="详情">
              <FaButton size="icon-sm" variant="ghost" @click="openDetail(node)">
                <FaIcon name="i-ri:eye-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canManage" text="编辑">
              <FaButton size="icon-sm" variant="ghost" @click="openEdit(node)">
                <FaIcon name="i-ri:edit-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canManage" text="重连">
              <FaButton size="icon-sm" variant="ghost" :disabled="reconnectBusy[node.id]" @click="reconnect(node)">
                <FaIcon name="i-ri:refresh-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canDelete" text="删除">
              <FaButton size="icon-sm" variant="ghost" class="text-destructive" @click="confirmDelete(node)">
                <FaIcon name="i-ri:delete-bin-line" />
              </FaButton>
            </FaTooltip>
          </div>
        </div>
      </div>

      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="() => load()"
        @size-change="() => { pager.page = 1; load() }"
      />
    </div>

    <!-- 表格视图：标准 FaTable 管理页 -->
    <template v-else>
      <FaResponsiveTable
        class="mcp-page-gap"
        v-loading="loading"
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
            <span class="cursor-pointer truncate font-medium text-primary hover:underline" @click="openDetail(row.original)">
              {{ row.original.name }}
            </span>
            <span class="truncate text-xs text-muted-foreground" :title="row.original.endpoint">
              {{ row.original.endpoint || '未配置控制信道' }}
            </span>
          </div>
        </template>
        <template #cell-status="{ row }">
          <div class="flex flex-wrap items-center justify-center gap-1">
            <NodeStatusTag :status="row.original.status" />
            <FaTag v-if="row.original.enabled === false" variant="secondary">
              已停用
            </FaTag>
          </div>
        </template>
        <template #cell-cpu="{ row }">
          <div v-if="row.original.stats && freshnessOf(row.original) !== 'offline'" class="flex items-center gap-2">
            <FaProgress :model-value="cpuPercentOf(row.original)" class="min-w-0 flex-1" />
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
            <FaProgress :model-value="memPercentOf(row.original)" class="min-w-0 flex-1" />
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
        <template #cell-operation="{ row }">
          <div class="mcp-op-cell">
            <FaTooltip text="详情">
              <FaButton size="icon-sm" variant="outline" @click="openDetail(row.original)">
                <FaIcon name="i-ri:eye-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canManage" text="编辑">
              <FaButton size="icon-sm" variant="outline" @click="openEdit(row.original)">
                <FaIcon name="i-ri:edit-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canManage" text="重连">
              <FaButton size="icon-sm" variant="outline" :disabled="reconnectBusy[row.original.id]" @click="reconnect(row.original)">
                <FaIcon name="i-ri:refresh-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canDelete" text="删除">
              <FaButton size="icon-sm" variant="destructive" @click="confirmDelete(row.original)">
                <FaIcon name="i-ri:delete-bin-line" />
              </FaButton>
            </FaTooltip>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.name }}</span>
                <div class="flex gap-1">
                  <NodeStatusTag :status="row.status" />
                  <FaTag v-if="row.enabled === false" variant="secondary">
                    已停用
                  </FaTag>
                </div>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">地址</span>
                  <span class="break-all">{{ row.endpoint || '未配置控制信道' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">CPU / 内存</span>
                  <span class="break-all">{{ row.stats && freshnessOf(row) !== 'offline' ? `${metricTextOf(row, cpuPercentOf(row))} · ${metricTextOf(row, memPercentOf(row))}` : '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">容器</span>
                  <span class="break-all">{{ row.stats?.containers?.length ?? '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">版本</span>
                  <span class="break-all">Agent {{ row.agentVersion || '-' }} · Docker {{ row.stats?.dockerVersion || row.dockerVersion || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">心跳</span>
                  <span class="break-all">{{ relativeSeenText(row.lastSeenAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openDetail(row)">
                  详情
                </FaButton>
                <FaButton v-if="canManage" size="sm" variant="outline" @click="openEdit(row)">
                  编辑
                </FaButton>
                <FaButton v-if="canManage" size="sm" variant="outline" :disabled="reconnectBusy[row.id]" @click="reconnect(row)">
                  重连
                </FaButton>
                <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmDelete(row)">
                  删除
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>

      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="() => load()"
        @size-change="() => { pager.page = 1; load() }"
      />
    </template>

    <NodeFormModal
      v-model:open="formOpen"
      :api="api"
      :node="editingNode"
      @created="onCreated"
      @saved="() => { toast.success('节点已保存'); load() }"
    />
  </FaPageMain>
</template>
