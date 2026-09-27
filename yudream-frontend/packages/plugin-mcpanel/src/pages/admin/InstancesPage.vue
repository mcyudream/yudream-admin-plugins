<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { McpNode, McpNodeContainerStat } from '../../types'
import { FaButton, FaButtonGroup, FaCard, FaCheckbox, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, FaTooltip, useFaModal, useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatMib, toNumberOr } from '../../composables/utils'
import InstanceFormModal from '../../components/InstanceFormModal.vue'
import MetricTrendChart from '../../components/MetricTrendChart.vue'

/** 实例列表：标准 FaTable 管理页（选择列批量操作 + 筛选 + 服务端分页），
 * 并提供卡片视图（实时 CPU/内存趋势 + 图标操作）。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()

const canUse = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.use))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const KIND_LABEL: Record<string, string> = {
  vanilla: '原版',
  paper: 'Paper',
  purpur: 'Purpur',
  folia: 'Folia',
  fabric: 'Fabric',
  forge: 'Forge',
  neoforge: 'NeoForge',
  quilt: 'Quilt',
  velocity: 'Velocity',
  bungee: 'Bungee',
  bedrock: '基岩版',
  generic: '通用',
}

const STATE_LABEL: Record<string, string> = {
  running: '运行中',
  starting: '启动中',
  exited: '已退出',
  created: '已创建',
  installing: '安装中',
  paused: '已暂停',
  stopped: '已停止',
  unknown: '未知',
}

const loading = ref(false)
const rows = ref<Record<string, unknown>[]>([])
const nodes = ref<McpNode[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', status: 'all', nodeId: 'all' })
const actingId = ref('')
const selectedIds = ref<string[]>([])
const batchLoading = ref(false)
const formOpen = ref(false)
const editing = ref<Record<string, unknown> | null>(null)
/** FaTable 暴露 TanStack table 实例，用于批量操作后重置选择状态。 */
const tableRef = ref<{ table?: { resetRowSelection: () => void } } | null>(null)

/** 视图模式：表格（默认）/ 卡片；卡片带实时资源趋势。 */
const VIEW_KEY = 'mcp-instances-view'
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
  { label: '运行中', value: 'running' },
  { label: '已退出', value: 'exited' },
  { label: '已创建', value: 'created' },
  { label: '安装中', value: 'installing' },
]

const nodeOptions = computed(() => [
  { label: '全部节点', value: 'all' },
  ...nodes.value.map(node => ({ label: node.name, value: node.id })),
])

const nodeNameMap = computed(() => {
  const map = new Map<string, string>()
  nodes.value.forEach((node) => {
    map.set(node.id, node.name)
  })
  return map
})

const columns: TableColumn<Record<string, unknown>>[] = [
  { type: 'selection', width: 46, fixed: 'left' },
  { accessorKey: 'name', header: '实例', minWidth: 200, fixed: 'left' },
  { id: 'state', header: '状态', width: 90, align: 'center' },
  { id: 'kind', header: '类型', width: 100, align: 'center' },
  { id: 'relation', header: '归属', width: 130, align: 'center' },
  { accessorKey: 'mcVersion', header: '版本', width: 100, align: 'center' },
  { accessorKey: 'image', header: '镜像', minWidth: 180 },
  { id: 'ports', header: '端口', minWidth: 130 },
  { id: 'memory', header: '内存', width: 100, align: 'right' },
  { id: 'operation', header: '操作', width: 230, align: 'center', fixed: 'right' },
]

function stateOf(row: Record<string, unknown>): string {
  // 后端统一口径：liveState = 节点 stats 快照实时状态（快照缺失回退 DB state）。
  // 与详情页、总览统计同一来源，前端不再自行拼接。状态筛选仍按 DB 值（后端过滤）。
  return String(row.liveState ?? row.state ?? 'unknown')
}

function stateLabel(row: Record<string, unknown>): string {
  return STATE_LABEL[stateOf(row)] || stateOf(row) || '-'
}

function stateTagVariant(row: Record<string, unknown>): 'default' | 'secondary' | 'outline' | 'destructive' {
  switch (stateOf(row)) {
    case 'running':
      return 'default'
    case 'installing':
      return 'secondary'
    case 'exited':
      return 'destructive'
    default:
      return 'outline'
  }
}

function portsText(row: Record<string, unknown>): string {
  const ports = row.ports as Array<{ hostPort: number, proto: string }> | undefined
  if (!ports?.length) {
    return '-'
  }
  return ports.map(p => `${p.hostPort}/${p.proto}`).join('、')
}

function memoryText(row: Record<string, unknown>): string {
  const mb = Number(row.memoryMb ?? 0)
  return mb > 0 ? `${mb} MB` : '-'
}

/** 代理父子关系摘要（后端 proxy-group 装饰字段）。 */
function proxyOf(row: Record<string, unknown>): { proxyInstanceId?: string, proxyName?: string, serverName?: string } | undefined {
  return row.proxy as { proxyInstanceId?: string, proxyName?: string, serverName?: string } | undefined
}

function proxyChildrenOf(row: Record<string, unknown>): Array<Record<string, unknown>> {
  return Array.isArray(row.proxyChildren) ? row.proxyChildren as Array<Record<string, unknown>> : []
}

/** 节点 stats.containers 按 instanceId 建索引，驱动卡片实时 CPU/内存展示。 */
const containerByInstance = computed(() => {
  const map = new Map<string, McpNodeContainerStat>()
  nodes.value.forEach((node) => {
    node.stats?.containers?.forEach((container) => {
      map.set(String(container.instanceId), container)
    })
  })
  return map
})

function liveCpuOf(row: Record<string, unknown>): number {
  const container = containerByInstance.value.get(String(row.id))
  return Math.min(100, Math.max(0, Math.round(toNumberOr(container?.cpuPercent))))
}

function liveMemUsedOf(row: Record<string, unknown>): number {
  const container = containerByInstance.value.get(String(row.id))
  return toNumberOr(container?.memUsedMb)
}

function liveMemPercentOf(row: Record<string, unknown>): number {
  const limitMb = toNumberOr(row.memoryMb)
  if (!limitMb) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((liveMemUsedOf(row) / limitMb) * 100)))
}

function liveMemTextOf(row: Record<string, unknown>): string {
  const used = liveMemUsedOf(row)
  if (!used) {
    return '-'
  }
  return `${formatMib(used)} / ${memoryText(row)}`
}

/** 页面打开期间的采样环（跨会话窗口由数据监控 overview 的 history 提供）。 */
const cpuSeries = reactive<Record<string, number[]>>({})
const memSeries = reactive<Record<string, number[]>>({})
const SERIES_LIMIT = 60

function pushSample(series: Record<string, number[]>, id: string, value: number) {
  if (!Number.isFinite(value)) {
    return
  }
  const next = [...(series[id] ?? []), Math.min(100, Math.max(0, value))]
  series[id] = next.length > SERIES_LIMIT ? next.slice(next.length - SERIES_LIMIT) : next
}

function recordSamples() {
  rows.value.forEach((row) => {
    const id = String(row.id)
    const container = containerByInstance.value.get(id)
    if (!container) {
      return
    }
    pushSample(cpuSeries, id, toNumberOr(container.cpuPercent))
    pushSample(memSeries, id, liveMemPercentOf(row))
  })
}

async function load() {
  loading.value = true
  try {
    const page = await extra.pageInstances(
      pager.page,
      pager.size,
      filters.keyword.trim() || undefined,
      filters.status === 'all' ? undefined : filters.status,
      filters.nodeId === 'all' ? undefined : filters.nodeId,
    ) as { records?: Record<string, unknown>[], total?: number }
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载实例列表失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadNodes() {
  try {
    const page = await api.pageNodes(1, 100) as { records?: McpNode[] }
    nodes.value = page.records ?? []
    recordSamples()
  }
  catch {
    nodes.value = []
  }
}

function search() {
  pager.page = 1
  void load()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = 'all'
  filters.nodeId = 'all'
  search()
}

function clearSelection() {
  tableRef.value?.table?.resetRowSelection()
}

function openCreate() {
  void router.push('/platform/plugins/mcpanel/admin/instances/create')
}

function openDetail(row: Record<string, unknown>) {
  void router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(row.id))}`)
}

function actionText(action: string) {
  return { start: '启动', stop: '停止', restart: '重启', kill: '强杀' }[action] ?? action
}

async function act(row: Record<string, unknown>, action: 'start' | 'stop' | 'restart' | 'kill') {
  if (actingId.value) {
    return
  }
  if (action === 'kill') {
    modal.confirm({
      title: '强制终止实例',
      content: `确认强杀实例「${row.name}」？未保存的数据将丢失。`,
      confirmButtonText: '强杀',
      onConfirm: async () => {
        await doAct(row, action)
      },
    })
    return
  }
  await doAct(row, action)
}

async function doAct(row: Record<string, unknown>, action: 'start' | 'stop' | 'restart' | 'kill') {
  actingId.value = String(row.id)
  try {
    await extra.instanceAction(String(row.id), action)
    toast.success(`已${actionText(action)}「${row.name}」`)
    void load()
  }
  catch (error) {
    toast.error(errorMessage(error, `${actionText(action)}失败`))
  }
  finally {
    actingId.value = ''
  }
}

function confirmDelete(row: Record<string, unknown>) {
  deleteTarget.value = row
  deletePurge.value = false
  deleteError.value = ''
  deleteOpen.value = true
}

/** 删除确认弹窗（回收站语义）：默认进回收站，勾选后跳过回收站直接永久删除。 */
const deleteOpen = ref(false)
const deleteTarget = ref<Record<string, unknown> | null>(null)
const deletePurge = ref(false)
const deleteError = ref('')
const deleting = ref(false)

function beforeDeleteClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  void submitDelete(done)
}

async function submitDelete(done: () => void) {
  const target = deleteTarget.value
  if (!target || deleting.value) {
    return
  }
  deleting.value = true
  deleteError.value = ''
  try {
    await extra.deleteInstance(String(target.id), deletePurge.value)
    done()
    toast.success(deletePurge.value ? `实例「${target.name}」及其数据已永久删除` : `实例「${target.name}」已删除，数据移入回收站`)
    if (rows.value.length === 1 && pager.page > 1) {
      pager.page -= 1
    }
    void load()
  }
  catch (error) {
    deleteError.value = errorMessage(error, '删除失败')
  }
  finally {
    deleting.value = false
  }
}

function onSelectionChange(selected: Record<string, unknown>[]) {
  selectedIds.value = selected.map(row => String(row.id))
}

async function batchAction(action: 'start' | 'stop' | 'restart' | 'kill') {
  if (!selectedIds.value.length || batchLoading.value) {
    return
  }
  const run = async () => {
    batchLoading.value = true
    try {
      const result = await extra.batchInstanceAction(action, selectedIds.value) as {
        results?: Array<{ id: string, ok?: boolean, error?: string }>
      }
      const results = result?.results ?? []
      const failed = results.filter(item => item.ok === false)
      if (failed.length) {
        toast.warning(`批量${actionText(action)}：成功 ${results.length - failed.length}，失败 ${failed.length}`)
      }
      else {
        toast.success(`已批量${actionText(action)} ${selectedIds.value.length} 个实例`)
      }
      clearSelection()
      void load()
    }
    catch (error) {
      toast.error(errorMessage(error, `批量${actionText(action)}失败`))
    }
    finally {
      batchLoading.value = false
    }
  }
  if (action === 'kill') {
    modal.confirm({
      title: '批量强杀',
      content: `确认强杀选中的 ${selectedIds.value.length} 个实例？未保存的数据将丢失。`,
      confirmButtonText: '强杀',
      onConfirm: run,
    })
    return
  }
  await run()
}

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void load()
  void loadNodes()
  // 轻量轮询节点快照（agents 每 5s 上报），驱动卡片实时 CPU/内存与趋势采样。
  pollTimer = setInterval(() => {
    if (document.hidden) {
      return
    }
    void loadNodes()
    void load()
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
  <FaPageHeader title="应用实例" description="Minecraft 服务器实例的生命周期与批量管理">
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
    <FaButton v-if="canManage" @click="openCreate">
      <FaIcon name="i-ri:magic-line" />
      创建服务器
    </FaButton>
    <FaButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/market')">
      应用市场
    </FaButton>
    <FaButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/quickstart')">
      快速开始
    </FaButton>
  </FaPageHeader>

  <FaPageMain>
    <!-- 卡片视图：实时资源 + 迷你趋势 + 图标操作 -->
    <div v-if="viewMode === 'card'" v-loading="loading">
      <div v-if="!rows.length" class="mcp-empty-hint">
        暂无实例，可通过「创建服务器」「应用市场」或「快速开始」安装
      </div>
      <div v-else class="mcp-card-grid">
        <div v-for="row in rows" :key="String(row.id)" class="mcp-card">
          <div class="mcp-card-head">
            <button type="button" class="mcp-card-title" :title="String(row.name ?? '')" @click="openDetail(row)">
              {{ row.name || '未命名实例' }}
            </button>
            <span class="mcp-spacer" />
            <FaTag :variant="stateTagVariant(row)">
              {{ stateLabel(row) }}
            </FaTag>
          </div>
          <div class="mcp-card-sub" :title="String(row.image ?? '')">
            {{ row.image || '-' }}
          </div>
          <div class="mcp-card-meta">
            <span>节点 {{ nodeNameMap.get(String(row.nodeId)) || row.nodeId || '-' }}</span>
            <span>{{ KIND_LABEL[String(row.kind)] || row.kind || '-' }}</span>
            <span v-if="row.mcVersion">v{{ row.mcVersion }}</span>
            <span>端口 {{ portsText(row) }}</span>
            <span>{{ memoryText(row) }}</span>
          </div>
          <div class="mcp-card-metrics">
            <div class="mcp-metric">
              <div class="mcp-metric-head">
                <span>CPU</span>
                <strong>{{ containerByInstance.get(String(row.id)) ? `${liveCpuOf(row)}%` : '-' }}</strong>
              </div>
              <MetricTrendChart :values="cpuSeries[String(row.id)] ?? []" :height="30" :max="100" name="CPU" />
            </div>
            <div class="mcp-metric">
              <div class="mcp-metric-head">
                <span>内存</span>
                <strong>{{ liveMemTextOf(row) }}</strong>
              </div>
              <MetricTrendChart :values="memSeries[String(row.id)] ?? []" :height="30" :max="100" name="内存" />
            </div>
          </div>
          <div class="mcp-card-ops">
            <FaTooltip text="详情">
              <FaButton size="icon-sm" variant="ghost" @click="openDetail(row)">
                <FaIcon name="i-ri:eye-line" />
              </FaButton>
            </FaTooltip>
            <template v-if="canUse">
              <FaTooltip v-if="stateOf(row) !== 'running'" text="启动">
                <FaButton size="icon-sm" variant="ghost" :disabled="actingId === String(row.id)" @click="act(row, 'start')">
                  <FaIcon name="i-ri:play-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip v-else text="停止">
                <FaButton size="icon-sm" variant="ghost" :disabled="actingId === String(row.id)" @click="act(row, 'stop')">
                  <FaIcon name="i-ri:stop-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip text="重启">
                <FaButton size="icon-sm" variant="ghost" :disabled="actingId === String(row.id)" @click="act(row, 'restart')">
                  <FaIcon name="i-ri:restart-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip text="强杀">
                <FaButton size="icon-sm" variant="ghost" class="text-destructive" :disabled="actingId === String(row.id)" @click="act(row, 'kill')">
                  <FaIcon name="i-ri:close-circle-line" />
                </FaButton>
              </FaTooltip>
            </template>
            <FaTooltip v-if="canManage" text="编辑">
              <FaButton size="icon-sm" variant="ghost" @click="editing = row; formOpen = true">
                <FaIcon name="i-ri:edit-line" />
              </FaButton>
            </FaTooltip>
            <FaTooltip v-if="canDelete" text="删除">
              <FaButton size="icon-sm" variant="ghost" class="text-destructive" @click="confirmDelete(row)">
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
        ref="tableRef"
        v-loading="loading"
        :columns="columns"
        :data="rows"
        row-key="id"
        selectable
        multiple
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[1080px]"
        border
        stripe
        column-visibility
        empty-text="暂无实例，可通过「创建服务器」「应用市场」或「快速开始」安装"
        @selection-change="onSelectionChange"
      >
        <template #toolbar>
          <div class="flex w-full flex-col gap-2">
            <FaSearchBar :show-toggle="false" class="w-full">
              <div class="grid w-full gap-2 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_160px_200px_auto]">
                <FaInput v-model="filters.keyword" clearable placeholder="实例名 / 备注" class="w-full" @keydown.enter="search" @clear="search" />
                <FaSelect v-model="filters.status" :options="STATUS_OPTIONS" class="w-full" @change="search" />
                <FaSelect v-model="filters.nodeId" :options="nodeOptions" class="w-full" @change="search" />
                <div class="flex items-center justify-end gap-2">
                  <FaButton variant="outline" @click="resetFilters">
                    重置
                  </FaButton>
                  <FaButton @click="search">
                    <FaIcon name="i-ri:search-line" />
                    查询
                  </FaButton>
                </div>
              </div>
            </FaSearchBar>
            <div v-if="selectedIds.length" class="flex flex-wrap items-center gap-2 rounded-md border border-dashed px-3 py-2">
              <span class="text-sm text-muted-foreground">已选 {{ selectedIds.length }} 个实例</span>
              <FaButton v-if="canUse" size="sm" variant="outline" :disabled="batchLoading" @click="batchAction('start')">
                批量启动
              </FaButton>
              <FaButton v-if="canUse" size="sm" variant="outline" :disabled="batchLoading" @click="batchAction('stop')">
                批量停止
              </FaButton>
              <FaButton v-if="canUse" size="sm" variant="outline" :disabled="batchLoading" @click="batchAction('restart')">
                批量重启
              </FaButton>
              <FaButton v-if="canUse" size="sm" variant="destructive" :disabled="batchLoading" @click="batchAction('kill')">
                批量强杀
              </FaButton>
              <FaButton size="sm" variant="ghost" @click="clearSelection">
                取消选择
              </FaButton>
            </div>
          </div>
        </template>

        <template #cell-name="{ row }">
          <div class="flex min-w-0 flex-col">
            <span class="cursor-pointer truncate font-medium text-primary hover:underline" @click="openDetail(row.original)">
              {{ row.original.name || '未命名实例' }}
            </span>
            <span class="truncate text-xs text-muted-foreground">
              {{ nodeNameMap.get(String(row.original.nodeId)) || row.original.nodeId || '-' }}
            </span>
          </div>
        </template>
        <template #cell-state="{ row }">
          <FaTag :variant="stateTagVariant(row.original)">
            {{ stateLabel(row.original) }}
          </FaTag>
        </template>
        <template #cell-kind="{ row }">
          {{ KIND_LABEL[String(row.original.kind)] || row.original.kind || '-' }}
        </template>
        <template #cell-relation="{ row }">
          <router-link
            v-if="proxyOf(row.original)?.proxyInstanceId"
            :to="`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(proxyOf(row.original)?.proxyInstanceId))}`"
            class="inline-flex items-center gap-1 text-primary hover:underline"
            :title="`所属代理 ${proxyOf(row.original)?.proxyName ?? ''}（子服 ${proxyOf(row.original)?.serverName ?? ''}）`"
          >
            <FaIcon name="i-ri:link" />
            子服 · {{ proxyOf(row.original)?.proxyName || '代理' }}
          </router-link>
          <router-link
            v-else-if="proxyChildrenOf(row.original).length"
            :to="`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(row.original.id))}`"
            class="inline-flex items-center gap-1 text-primary hover:underline"
            :title="`代理子服：${proxyChildrenOf(row.original).map(child => String(child.serverName ?? '')).filter(Boolean).join('、')}`"
          >
            <FaIcon name="i-ri:shuffle-line" />
            代理 · {{ proxyChildrenOf(row.original).length }} 子服
          </router-link>
          <span v-else class="text-muted-foreground">-</span>
        </template>
        <template #cell-image="{ row }">
          <span class="block truncate" :title="String(row.original.image ?? '')">{{ row.original.image || '-' }}</span>
        </template>
        <template #cell-ports="{ row }">
          <span class="block truncate" :title="portsText(row.original)">{{ portsText(row.original) }}</span>
        </template>
        <template #cell-memory="{ row }">
          {{ memoryText(row.original) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="mcp-op-cell">
            <FaTooltip text="详情">
              <FaButton size="icon-sm" variant="outline" @click="openDetail(row.original)">
                <FaIcon name="i-ri:eye-line" />
              </FaButton>
            </FaTooltip>
            <template v-if="canUse">
              <FaTooltip v-if="stateOf(row.original) !== 'running'" text="启动">
                <FaButton size="icon-sm" variant="outline" :disabled="actingId === String(row.original.id)" @click="act(row.original, 'start')">
                  <FaIcon name="i-ri:play-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip v-else text="停止">
                <FaButton size="icon-sm" variant="outline" :disabled="actingId === String(row.original.id)" @click="act(row.original, 'stop')">
                  <FaIcon name="i-ri:stop-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip text="重启">
                <FaButton size="icon-sm" variant="outline" :disabled="actingId === String(row.original.id)" @click="act(row.original, 'restart')">
                  <FaIcon name="i-ri:restart-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip text="强杀">
                <FaButton size="icon-sm" variant="outline" class="text-destructive" :disabled="actingId === String(row.original.id)" @click="act(row.original, 'kill')">
                  <FaIcon name="i-ri:close-circle-line" />
                </FaButton>
              </FaTooltip>
            </template>
            <FaTooltip v-if="canManage" text="编辑">
              <FaButton size="icon-sm" variant="outline" @click="editing = row.original; formOpen = true">
                <FaIcon name="i-ri:edit-line" />
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
                <span class="min-w-0 break-words text-base font-semibold">{{ row.name || '未命名实例' }}</span>
                <FaTag :variant="stateTagVariant(row)">
                  {{ stateLabel(row) }}
                </FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">节点</span>
                  <span class="break-all">{{ nodeNameMap.get(String(row.nodeId)) || row.nodeId || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">类型</span>
                  <span class="break-all">{{ KIND_LABEL[String(row.kind)] || row.kind || '-' }}{{ row.mcVersion ? ` · v${row.mcVersion}` : '' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">端口</span>
                  <span class="break-all">{{ portsText(row) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">内存</span>
                  <span class="break-all">{{ memoryText(row) }}</span>
                </div>
                <div v-if="row.image" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">镜像</span>
                  <span class="break-all">{{ row.image }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openDetail(row)">
                  详情
                </FaButton>
                <template v-if="canUse">
                  <FaButton v-if="stateOf(row) !== 'running'" size="sm" variant="outline" :disabled="actingId === String(row.id)" @click="act(row, 'start')">
                    启动
                  </FaButton>
                  <FaButton v-else size="sm" variant="outline" :disabled="actingId === String(row.id)" @click="act(row, 'stop')">
                    停止
                  </FaButton>
                  <FaButton size="sm" variant="outline" :disabled="actingId === String(row.id)" @click="act(row, 'restart')">
                    重启
                  </FaButton>
                  <FaButton size="sm" variant="outline" class="text-destructive" :disabled="actingId === String(row.id)" @click="act(row, 'kill')">
                    强杀
                  </FaButton>
                </template>
                <FaButton v-if="canManage" size="sm" variant="outline" @click="editing = row; formOpen = true">
                  编辑
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

    <InstanceFormModal
      v-model:open="formOpen"
      :sdk="sdk"
      :instance="editing"
      :nodes="nodes"
      @saved="() => { toast.success('实例已保存'); load() }"
    />

    <FaModal
      v-model="deleteOpen"
      title="删除实例"
      :show-cancel-button="true"
      :confirm-button-text="deletePurge ? '永久删除' : '删除（进回收站）'"
      :confirm-button-loading="deleting"
      :before-close="beforeDeleteClose"
    >
      <div class="flex flex-col gap-3">
        <p>
          确认删除实例「<b>{{ deleteTarget?.name }}</b>」？
        </p>
        <p class="text-muted-foreground text-xs leading-5">
          删除后实例的数据目录将移入节点回收站，保留期内可在
          <b>回收站</b> 页面找回（重建实例）或打包下载；超过节点保留期后自动永久清除。
        </p>
        <FaCheckbox v-model="deletePurge">
          <span class="text-destructive">跳过回收站，连同数据目录一起永久删除（不可恢复）</span>
        </FaCheckbox>
        <p v-if="deleteError" class="text-destructive text-xs">{{ deleteError }}</p>
      </div>
    </FaModal>
  </FaPageMain>
</template>
