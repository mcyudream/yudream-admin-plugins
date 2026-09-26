<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpNodeStats } from '../../types.ts'
import { FaAlert, FaButton, FaCard, FaDescriptions, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaSelect, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra.ts'
import { createMcPanelApi } from '../../api/mcpanel-api.ts'
import FileBrowserPanel from '../../components/FileBrowserPanel.vue'
import FileEditorModal from '../../components/FileEditorModal.vue'
import MetricTrendChart from '../../components/MetricTrendChart.vue'
import TerminalConsole from '../../components/TerminalConsole.vue'
import UploadTasksPanel from '../../components/UploadTasksPanel.vue'
import { useTerminalConsole } from '../../composables/useTerminalConsole.ts'
import { useBackupTargets } from '../../composables/useBackupTargets.ts'
import { useInstanceUploads } from '../../composables/useInstanceUploads.ts'
import { useNodeEventsStream } from '../../composables/useNodeEventsStream.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { unwrapStreamEvent } from '../../composables/sse-frames.ts'
import { createSseTransport } from '../../composables/sseTransport.ts'
import { errorMessage, formatBytes, formatDateTime, formatMib, relativeSeenText, toNumberOr } from '../../composables/utils.ts'
import { createTerminalDecoder } from '../../utils/terminalDecode.ts'
import { createTextFileAccess } from '../../utils/textFileAccess.ts'
import { MC_CONSOLE_COMMANDS } from '../../utils/commandComplete.ts'
import { withTimeout } from '../../utils/fileContent.ts'

/**
 * 实例控制台：共享终端工作区（TerminalConsole）+ 实例侧栏（文件树 / 命令 / 玩家）。
 *
 * 输出流语义：
 * - 初始化顺序明确：先 output.read 尾部补齐（历史），完成后再 subscribe + 打开
 *   SSE；实时流开启后 cursor 不再推进，断线重连只给诚实提示（「中断期间输出
 *   可能缺失」），不用旧 cursor 盲拉造成整段重放；
 * - instance.output.subscribe 是节点侧实例级 attach 泵（跨浏览器共享），页面
 *   关闭/切换只断开本地 SSE，一律不调用 unsubscribe，避免杀死其他订阅者；
 * - stream.gap 为队列溢出通知：以系统行提示丢失，不写入日志内容；
 * - 路由复用切换实例（instanceId 变化）时全量重置：停流、清屏、换历史键、
 *   重拉详情/备份/玩家并按新 id 重开输出流与节点统计流。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const extra = createMcPanelExtra(props.sdk)
const panelApi = createMcPanelApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()
const route = useRoute()

const canUse = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.use))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))

const instanceId = computed(() => String(route.params.id ?? ''))

/** 上传任务（异步分片）：本页发起的上传与文件页发起的都在此恢复显示。 */
const { tasks: uploadTaskItems, cancelTask: cancelUploadTask, isHashing: uploadHashing } =
  useInstanceUploads(props.sdk, instanceId)
const listPath = '/platform/plugins/mcpanel/admin/instances'
const filesPath = computed(() => `${listPath}/${instanceId.value}/files`)

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

const COMMON_COMMANDS = ['list', 'say ', 'time set day', 'weather clear', 'save-all flush', 'help', 'tps', 'stop']

const loading = ref(false)
const loadError = ref('')
const instance = ref<Record<string, unknown> | null>(null)
let viewGeneration = 0
let disposed = false
let stateRefreshing = false

const termDecoder = createTerminalDecoder(() => term.encoding.value)

const term = useTerminalConsole({
  mode: 'mc',
  historyKey: () => `mcp-hist-${instanceId.value}`,
  builtins: () => MC_CONSOLE_COMMANDS,
  contextWords: () => playerNames.value,
  onSubmit: sendCommand,
  onEncodingChanged(encoding) {
    termDecoder.reset()
    if (outputConnected.value) {
      // 实时流持续中不整段重放历史：分界线提示生效范围。
      term.appendDivider(`输出解码已切换为 ${encoding.toUpperCase()}，仅对后续输出与下次重载生效`)
    }
    else {
      consoleCursor.value = ''
      term.clear()
      void pollOutput(true)
    }
  },
})

let playersTimer: ReturnType<typeof setInterval> | null = null

// ---------- 实时资源监控（节点 SSE 的容器维度统计） ----------

const liveCpuSeries = ref<number[]>([])
const liveMemSeries = ref<number[]>([])
const containerState = ref('')
let streamNodeId = ''

const { latestStats, start: startNodeStream, stop: stopNodeStream } = useNodeEventsStream({
  onStats: onNodeStats,
})

const liveContainer = computed(() => (latestStats.value?.containers ?? [])
  .find(item => String(item.instanceId) === instanceId.value) ?? null)

const liveCpuNow = computed(() => {
  const series = liveCpuSeries.value
  return series.length ? `${series[series.length - 1]}%` : '-'
})

const liveMemNow = computed(() => {
  const container = liveContainer.value
  if (!container) {
    return '-'
  }
  const limitMb = toNumberOr(instance.value?.memoryMb)
  return `${formatMib(toNumberOr(container.memUsedMb))}${limitMb ? ` / ${limitMb} MB` : ''}`
})

/** 实时落点与历史同节奏（后端 30s/点）：图表按点序均匀分布，密度不一致会让时间轴失真。 */
const LIVE_SAMPLE_MS = 30_000
let lastLiveSampleAt = 0

function pushLiveSample(series: number[], value: number) {
  if (!Number.isFinite(value)) {
    return
  }
  series.push(Math.min(100, Math.max(0, value)))
  // 窗口滚动：容量跟随当前回看窗口（30s/点），超出后裁掉最老的点
  const cap = Math.round(metricWindow.value / LIVE_SAMPLE_MS) + 4
  if (series.length > cap) {
    series.splice(0, series.length - cap)
  }
}

// ---------- 性能历史（环形窗口回看 + 实时续接） ----------

const metricWindows = [
  { label: '1 小时', value: 3_600_000 },
  { label: '6 小时', value: 21_600_000 },
  { label: '24 小时', value: 86_400_000 },
]
const metricWindow = ref(3_600_000)
const metricLoading = ref(false)

async function loadMetricsHistory() {
  const id = instanceId.value
  if (!id) {
    return
  }
  metricLoading.value = true
  try {
    const result = await withTimeout(extra.instanceMetrics(id, metricWindow.value), 15_000, '历史指标读取超时') as {
      points?: Array<[number, number, number]>
    }
    if (id !== instanceId.value) {
      return
    }
    const points = result?.points ?? []
    const limitMb = toNumberOr(instance.value?.memoryMb)
    liveCpuSeries.value = points.map(point => Math.round(Number(point[1] ?? 0)))
    // 历史点的内存存的是绝对 MB（memUsedMb），换算成与实时一致的「占配置内存百分比」，
    // 否则几百上千的 MB 值在 max=100 的图上全部出界，历史段看起来是空白。
    liveMemSeries.value = points.map((point) => {
      const memUsedMb = Number(point[2] ?? 0)
      return limitMb > 0 ? Math.round((memUsedMb / limitMb) * 100) : 0
    })
    // 回填已覆盖到最近一个采样周期，实时落点延迟一个周期再开始，保持等密度
    lastLiveSampleAt = Date.now()
  }
  catch {
    // 历史不可用时保持实时曲线（可能从未运行过）。
  }
  finally {
    metricLoading.value = false
  }
}

function selectMetricWindow(windowMs: number) {
  if (metricWindow.value === windowMs) {
    return
  }
  metricWindow.value = windowMs
  void loadMetricsHistory()
}

// ---------- 核心安装进度（install.run 任务跟踪装饰字段） ----------

interface InstallFileRow {
  path?: string
  bytes?: number | string
  total?: number | string
  error?: string
}

/** 节点 0.5.0+ task.get 的 files 快照；旧节点无此字段。 */
interface InstallFilesSnapshot {
  total?: number | string
  done?: number | string
  failed?: number | string
  active?: InstallFileRow[]
  failedList?: InstallFileRow[]
}

interface InstallTaskRow {
  fileName?: string
  state?: string
  progress?: number | string
  error?: string
  startedAt?: number | string
  files?: InstallFilesSnapshot
}

const installTasks = computed<InstallTaskRow[]>(() => {
  const wrapped = instance.value?.installTask as { tasks?: InstallTaskRow[] } | undefined
  return wrapped?.tasks ?? []
})
const activeInstall = computed(() =>
  installTasks.value.find(task => task.state === 'running') ?? null)
const recentInstall = computed(() =>
  installTasks.value.find(task => task.state !== 'running') ?? null)

const installPercent = computed(() => {
  const task = activeInstall.value
  if (!task) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round(Number(task.progress ?? 0) * 100)))
})

const activeInstallFiles = computed(() => activeInstall.value?.files ?? null)
const installTotalCount = computed(() => Math.trunc(toNumberOr(activeInstallFiles.value?.total)))
const installDoneCount = computed(() => Math.trunc(toNumberOr(activeInstallFiles.value?.done)))
const installFailedCount = computed(() => Math.trunc(toNumberOr(activeInstallFiles.value?.failed)))
const installActiveRows = computed(() => (activeInstallFiles.value?.active ?? []).slice(0, 8))
const installFailedRows = computed(() => (activeInstallFiles.value?.failedList ?? []).slice(0, 8))
const installHiddenFailed = computed(() =>
  Math.max(0, installFailedCount.value - installFailedRows.value.length))
const recentFailedCount = computed(() =>
  Math.trunc(toNumberOr(recentInstall.value?.files?.failed)))

// 有活动安装任务时 2s 快速轮询详情（跟踪进度），无则回落 10s 常规节奏。
let installTimer: ReturnType<typeof setInterval> | null = null

function syncInstallPolling() {
  const need = Boolean(activeInstall.value) && !disposed
  if (need && installTimer == null) {
    installTimer = setInterval(() => {
      if (!document.hidden) {
        void refreshInstanceState()
      }
    }, 2_000)
  }
  else if (!need && installTimer != null) {
    clearInterval(installTimer)
    installTimer = null
  }
}

watch(activeInstall, () => syncInstallPolling())

/** 安装失败/卡死后的恢复：按面板落库的最近一次安装计划原样重建，防重复提交。 */
const installRetrying = ref(false)

async function retryInstall() {
  if (installRetrying.value) {
    return
  }
  installRetrying.value = true
  try {
    await extra.retryInstanceInstall(instanceId.value)
    toast.success('已重新发起安装，按计划原样重建下载')
    await refreshInstanceState()
  }
  catch (error) {
    toast.error(errorMessage(error, '重试安装失败'))
  }
  finally {
    installRetrying.value = false
  }
}

function onNodeStats(stats: McpNodeStats) {
  const container = (stats.containers ?? []).find(item => String(item.instanceId) === instanceId.value)
  if (!container) {
    return
  }
  containerState.value = String(container.state ?? '')
  // SSE 5s 级推送只负责状态实时性；曲线落点按 30s 节流，与历史采样等密度
  const now = Date.now()
  if (now - lastLiveSampleAt < LIVE_SAMPLE_MS) {
    return
  }
  lastLiveSampleAt = now
  pushLiveSample(liveCpuSeries.value, Math.round(toNumberOr(container.cpuPercent)))
  const limitMb = toNumberOr(instance.value?.memoryMb)
  const memPercent = limitMb > 0 ? (toNumberOr(container.memUsedMb) / limitMb) * 100 : 0
  pushLiveSample(liveMemSeries.value, Math.round(memPercent))
}

/** 实例详情载入后订阅其所在节点的事件流；换实例/换节点时由 bootstrap 重置重订阅。 */
watch(instance, (inst) => {
  const nodeId = String(inst?.nodeId ?? '')
  if (!nodeId || nodeId === streamNodeId) {
    return
  }
  streamNodeId = nodeId
  startNodeStream(panelApi.nodeEventsUrl(nodeId), nodeId)
})

// ---------- 文件浏览器（点击打开统一编辑器弹窗） ----------

const browserRef = ref<InstanceType<typeof FileBrowserPanel> | null>(null)
const editorOpen = ref(false)
const editorPath = ref('')
const editorDirectory = ref('')
const editorAccess = computed(() => createTextFileAccess({
  readChunk: (path, offset, length) => extra.readFileChunk(instanceId.value, path, offset, length),
  write: (path, content, charset) => extra.writeFile(instanceId.value, path, content, 'base64', charset),
}))

async function browserList(path: string, keyword?: string) {
  const result = await extra.listFiles(instanceId.value, path, 1, 200, keyword) as {
    entries?: Array<{ name?: string, path?: string, isDir?: boolean, size?: number }>
    total?: number | string
  }
  return {
    entries: (result?.entries ?? []).map(entry => ({
      name: String(entry.name ?? ''),
      isDir: Boolean(entry.isDir),
      size: Number(entry.size ?? 0),
      path: String(entry.path ?? (path ? `${path}/${entry.name}` : String(entry.name))),
    })),
    total: Number(result?.total ?? 0) || undefined,
  }
}

function parentDirOf(path: string): string {
  const index = path.lastIndexOf('/')
  return index > 0 ? path.slice(0, index) : ''
}

function openFileFromBrowser(path: string) {
  editorPath.value = path
  editorDirectory.value = parentDirOf(path)
  editorOpen.value = true
}

/** 备份行：type=local 节点本机档（可恢复/删除）；export/offsite 宿主备份中心任务（只读展示）。 */
interface BackupRow {
  key: string
  type: 'local' | 'export' | 'offsite'
  file?: string
  size?: number
  at: number
  jobId?: string
  status?: string
  percent?: number
  message?: string
  archiveName?: string
  targetCode?: string
  targetName?: string
}

const backupRows = ref<BackupRow[]>([])
const backupLoading = ref(false)
const backupCreating = ref(false)
const backupLastCreate = ref<{ success?: boolean, error?: string, file?: string, finishedAt?: number } | null>(null)
const restoringFile = ref('')
const deletingFile = ref('')
const backupColumns: TableColumn<BackupRow>[] = [
  { id: 'kind', header: '类型', width: 120 },
  { accessorKey: 'file', header: '备份文件 / 任务', minWidth: 200 },
  { accessorKey: 'size', header: '大小', width: 100, align: 'center' },
  { id: 'status', header: '状态', width: 130 },
  { accessorKey: 'at', header: '时间', width: 160 },
  { id: 'operation', header: '操作', width: 160, align: 'center' },
]

function backupKindLabel(row: BackupRow) {
  if (row.type === 'local') return '本地'
  if (row.type === 'export') return '本机导出'
  return `异地 · ${row.targetName || row.targetCode || '-'}`
}

function backupStatusLabel(row: BackupRow) {
  switch (String(row.status ?? '')) {
    case 'QUEUED': return '排队中'
    case 'RUNNING': return `执行中 ${Number(row.percent ?? 0)}%`
    case 'SUCCEEDED': return '成功'
    case 'FAILED': return '失败'
    default: return ''
  }
}

function confirmDeleteBackup(row: BackupRow) {
  modal.confirm({
    title: '删除备份',
    content: `确认删除备份「${row.file}」？删除后不可恢复。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      deletingFile.value = String(row.file)
      try {
        await extra.deleteBackup(instanceId.value, String(row.file))
        toast.success('备份已删除')
        void loadBackups()
      }
      catch (e) {
        toast.error(errorMessage(e, '删除备份失败'))
      }
      finally {
        deletingFile.value = ''
      }
    },
  })
}

/** 恢复备份：破坏性覆盖，必须显式确认 + busy 反馈。 */
function confirmRestoreBackup(row: BackupRow) {
  modal.confirm({
    title: '恢复备份',
    content: `确认从「${row.file}」恢复？恢复要求实例处于停止状态，且会覆盖当前数据目录内容。`,
    confirmButtonText: '恢复',
    onConfirm: async () => {
      restoringFile.value = String(row.file)
      try {
        await extra.restoreBackup(instanceId.value, String(row.file))
        toast.success('已请求恢复，节点将停止实例并回滚数据')
      }
      catch (e) {
        toast.error(errorMessage(e, '恢复失败'))
      }
      finally {
        restoringFile.value = ''
      }
    },
  })
}

// ---------- 创建备份（本地 / 异地） ----------

const backupModalOpen = ref(false)
const backupSubmitting = ref(false)
const backupModalError = ref('')
const backupTarget = ref<string>('local')
const backupTargetCode = ref('')
const backupTargets = useBackupTargets(props.sdk)

/** 异地目标选项：SDK 目录可用时下拉选择；否则回落手输编码。 */
const backupTargetOptions = computed(() => backupTargets.targets.value.map(target => ({
  label: `${target.name}（${target.code}）`,
  value: target.code,
})))

function openBackupModal() {
  backupTarget.value = 'local'
  backupTargetCode.value = ''
  backupModalError.value = ''
  backupModalOpen.value = true
  void backupTargets.load()
}

/** 打包轮询：受理后列表每 3s 刷新，直到 in-flight 状态结束并按结果提示一次。 */
const backupPoll = { timer: null as ReturnType<typeof setInterval> | null, acceptedAt: 0, notified: false }

function stopBackupPolling() {
  if (backupPoll.timer) {
    clearInterval(backupPoll.timer)
    backupPoll.timer = null
  }
}

function startBackupPolling(acceptedAt: number) {
  stopBackupPolling()
  backupPoll.acceptedAt = acceptedAt
  backupPoll.notified = false
  backupPoll.timer = setInterval(() => {
    void loadBackups().then(() => {
      const last = backupLastCreate.value
      if (backupPoll.notified) {
        stopBackupPolling()
        return
      }
      if (backupCreating.value || !last || Number(last.finishedAt ?? 0) < backupPoll.acceptedAt) {
        return
      }
      backupPoll.notified = true
      stopBackupPolling()
      if (last.success) toast.success(`备份完成：${last.file ?? ''}`)
      else toast.error(`备份失败：${last.error || '未知原因'}`)
    })
  }, 3000)
}

function submitBackup(close: () => void) {
  const code = backupTargetCode.value.trim()
  if (backupTarget.value === 'offsite' && !/^[a-z0-9][a-z0-9-]{0,63}$/.test(code)) {
    backupModalError.value = '请选择异地目标（或手输目标编码：小写字母/数字/连字符）'
    return
  }
  backupSubmitting.value = true
  backupModalError.value = ''
  const acceptedAt = Date.now()
  const request = backupTarget.value === 'local'
    ? extra.createBackup(instanceId.value)
    : extra.triggerOffsiteBackup(instanceId.value, code)
  request.then(() => {
    close()
    if (backupTarget.value === 'local') {
      toast.success('备份已受理，节点打包完成后在列表展示（大世界可能需要数分钟）')
      startBackupPolling(acceptedAt)
    }
    else {
      toast.success('已提交宿主数据备份中心，可在备份列表查看进度')
    }
    void loadBackups()
  }).catch((e) => {
    backupModalError.value = errorMessage(e, '备份提交失败')
  }).finally(() => {
    backupSubmitting.value = false
  })
}

// ---------- 备份保留策略（keepCount/keepDays，0=不限） ----------

const policyKeepCount = ref(0)
const policyKeepDays = ref(0)
const policySaving = ref(false)

async function loadBackupPolicy() {
  try {
    const result = await extra.backupPolicy(instanceId.value) as { keepCount?: number, keepDays?: number }
    policyKeepCount.value = Number(result.keepCount ?? 0)
    policyKeepDays.value = Number(result.keepDays ?? 0)
  }
  catch { /* 策略读取失败按不限展示 */ }
}

function saveBackupPolicy() {
  policySaving.value = true
  extra.saveBackupPolicy(instanceId.value, Math.max(0, Number(policyKeepCount.value) || 0), Math.max(0, Number(policyKeepDays.value) || 0))
    .then(() => {
      toast.success('保留策略已保存，超出保留策略的旧备份将由节点自动清理')
      void loadBackups()
    })
    .catch((e) => {
      toast.error(errorMessage(e, '保存保留策略失败'))
    })
    .finally(() => {
      policySaving.value = false
    })
}

const state = computed(() => {
  const live = String(containerState.value || '')
  if (live && live !== 'unknown') return live
  // SSE 未连接时回退后端统一口径（liveState=stats 快照优先），再回退 DB。
  const rest = String(instance.value?.liveState ?? '')
  if (rest && rest !== 'unknown') return rest
  return String(instance.value?.state ?? 'unknown')
})
const stateLabel = computed(() => STATE_LABEL[state.value] || state.value)
const kindLabel = computed(() => KIND_LABEL[String(instance.value?.kind ?? '')] || String(instance.value?.kind ?? '-'))
// 运行系 = running + starting：启动中容器已占资源，可看控制台/玩家，但还不能发命令。
const running = computed(() => state.value === 'running' || state.value === 'starting')
const consoleCursor = ref('')
const pollError = ref('')
const sendError = ref('')

/** TCP 端口映射（标题栏端口标签 + 玩家探测目标说明）。 */
const gamePort = computed(() => {
  const ports = (instance.value?.ports ?? []) as Array<{ hostPort?: number, containerPort?: number, proto?: string }>
  return ports.find(item => (item.proto ?? 'tcp') === 'tcp') ?? ports[0]
})

/** 代理父子关系（后端按 proxy-group 装饰）：子服显示所属代理，代理显示子服数量摘要。 */
const proxyInfo = computed(() => instance.value?.proxy as {
  proxyInstanceId?: string, proxyName?: string, serverName?: string
} | undefined)
const proxyChildren = computed(() => (instance.value?.proxyChildren ?? []) as Array<{
  serverName?: string, instanceId?: string, instanceName?: string, state?: string
}>)

// ---------- 在线玩家 ----------

interface PlayersInfo {
  reachable: boolean
  online?: number
  max?: number
  latencyMs?: number | null
  reason?: string
  players?: Array<{ name?: string, id?: string }>
}

const players = ref<PlayersInfo | null>(null)
const playersLoading = ref(false)
const playerNames = computed(() => (players.value?.players ?? [])
  .map(item => String(item.name ?? '')).filter(Boolean))

async function loadPlayers() {
  if (!instanceId.value || playersLoading.value || disposed) return
  const epoch = viewGeneration, id = instanceId.value
  playersLoading.value = true
  try {
    const result = await withTimeout(extra.instancePlayers(id), 12_000, '玩家探测超时') as PlayersInfo
    if (epoch === viewGeneration) players.value = result
  }
  catch { if (epoch === viewGeneration) players.value = { reachable: false, reason: '探测暂不可用' } }
  finally { if (epoch === viewGeneration) playersLoading.value = false }
}

/** 点击玩家名 → 插入命令输入框末尾（与补全共享词表语义）。 */
function fillCommandWord(word: string) {
  const current = term.commandText.value
  term.commandText.value = current && !current.endsWith(' ') ? `${current} ${word}` : current + word
}

// ---------- TPS 自动探测（Paper 系：周期代发 tps 命令，从控制台输出解析） ----------

const TPS_LINE = /TPS from last 1m, 5m, 15m:\s*([0-9.]+),\s*([0-9.]+),\s*([0-9.]+)/
const tps = ref<number | null>(null)
let tpsTimer: ReturnType<typeof setInterval> | null = null
const tpsSupported = computed(() =>
  ['paper', 'purpur', 'folia'].includes(String(instance.value?.kind ?? '')))

/** 控制台输出增量里解析 Paper 的 TPS 行（取 1m 值）；对所有类型解析无害。 */
function scanTpsChunk(chunk: string) {
  const match = TPS_LINE.exec(chunk.slice(-4000))
  if (match) {
    tps.value = Number(match[1])
  }
}

function startTpsPolling() {
  stopTpsPolling()
  tpsTimer = setInterval(() => {
    const id = instanceId.value
    if (!id || !running.value || document.hidden || disposed || !tpsSupported.value) {
      return
    }
    void extra.tpsProbe(id).catch(() => { /* 节点未就绪等：下轮重试 */ })
  }, 60_000)
}

function stopTpsPolling() {
  if (tpsTimer) {
    clearInterval(tpsTimer)
    tpsTimer = null
  }
}

const basicItems = computed(() => {
  const item = instance.value
  return [
    { label: '名称', value: String(item?.name ?? '-') },
    { label: '类型', value: kindLabel.value },
    { label: '状态', value: stateLabel.value },
    { label: 'MC 版本', value: String(item?.mcVersion || '-') },
    { label: '镜像', value: String(item?.image ?? '-') },
    { label: '节点', value: String(item?.nodeId ?? '-') },
    { label: '内存', value: item?.memoryMb ? `${item.memoryMb} MB` : '-' },
    { label: '端口', value: gamePort.value?.hostPort ? `${gamePort.value.hostPort}/${gamePort.value.proto ?? 'tcp'}` : '-' },
    ...(proxyInfo.value?.proxyInstanceId
      ? [{ label: '所属代理', value: `${proxyInfo.value.proxyName}（子服 ${proxyInfo.value.serverName ?? '-'}）` }]
      : []),
    ...(proxyChildren.value.length
      ? [{ label: '代理子服', value: `${proxyChildren.value.length} 个（见「代理与子服」页）` }]
      : []),
    { label: '创建时间', value: formatDateTime(item?.createdAt as number | string | undefined) },
    { label: '实例 ID', value: instanceId.value },
  ]
})

interface AuthlibInjectionView {
  supported?: boolean
  reason?: string
  enabled?: boolean
  running?: boolean
  apiRoot?: string
}

interface PlaytimeInjectionView {
  supported?: boolean
  reason?: string
  enabled?: boolean
  running?: boolean
  dir?: string
  matchedName?: string
}

const playtimeInjection = ref<PlaytimeInjectionView | null>(null)
const playtimeBusy = ref(false)

async function loadPlaytimeInjection() {
  try {
    playtimeInjection.value = await extra.playtimeInjection(instanceId.value) as PlaytimeInjectionView
  }
  catch {
    // 无查看权限或端点不可达：卡片降级展示
    playtimeInjection.value = null
  }
}

async function togglePlaytimeInjection(enabled: boolean) {
  if (playtimeBusy.value) {
    return
  }
  playtimeBusy.value = true
  try {
    playtimeInjection.value = await extra.applyPlaytimeInjection(instanceId.value, enabled) as PlaytimeInjectionView
    toast.success(enabled
      ? `已开启在线时长注入：制品已放入 ${playtimeInjection.value?.dir || '插件/模组'} 目录，启动实例后生效`
      : '已关闭在线时长注入：制品文件已从实例目录移除')
  }
  catch (error) {
    toast.error(errorMessage(error, '切换在线时长注入失败'))
    void loadPlaytimeInjection()
  }
  finally {
    playtimeBusy.value = false
  }
}

const authlibInjection = ref<AuthlibInjectionView | null>(null)
const authlibBusy = ref(false)

async function loadAuthlibInjection() {
  try {
    authlibInjection.value = await extra.authlibInjection(instanceId.value) as AuthlibInjectionView
  }
  catch {
    // 无查看权限或端点不可达：卡片降级展示
    authlibInjection.value = null
  }
}

async function toggleAuthlibInjection(enabled: boolean) {
  if (authlibBusy.value) {
    return
  }
  authlibBusy.value = true
  try {
    authlibInjection.value = await extra.applyAuthlibInjection(instanceId.value, enabled) as AuthlibInjectionView
    toast.success(enabled ? '已开启注入并下发注入 jar，启动实例后生效' : '已关闭注入，启动命令已移除 -javaagent')
    // 启动命令已变更，刷新基本信息展示
    void load()
  }
  catch (error) {
    toast.error(errorMessage(error, '切换注入失败'))
    void loadAuthlibInjection()
  }
  finally {
    authlibBusy.value = false
  }
}

async function load() {
  const epoch = viewGeneration, id = instanceId.value
  if (!id) { loadError.value = '缺少实例 ID'; return }
  loading.value = true
  loadError.value = ''
  try {
    const result = await withTimeout(extra.instanceDetail(id), 15_000, '实例详情加载超时') as Record<string, unknown>
    if (!disposed && epoch === viewGeneration) instance.value = result
  }
  catch (error) { if (epoch === viewGeneration) loadError.value = errorMessage(error, '加载实例失败') }
  finally { if (epoch === viewGeneration) loading.value = false }
}

async function loadFiles() {
  // 文件浏览器挂载后自行加载根目录；实例启动后（act 内）调用本函数刷新当前目录。
  await browserRef.value?.reload()
}

/** 历史尾部补齐：仅在订阅前初始化与 start/restart（cursor 重置）后有界执行；
 * 实时流开启后 cursor 不推进，不再用它补拉（避免整段重放）。 */
async function pollOutput(force = false) {
  if (!instanceId.value || outputConnected.value || disposed || (!running.value && !force)) return
  const epoch = viewGeneration, id = instanceId.value
  try {
    const result = await withTimeout(extra.instanceOutput(id, consoleCursor.value || undefined, 250), 15_000, '历史日志读取超时') as {
      text?: string, data?: string, nextCursor?: string, truncated?: boolean
    }
    if (epoch !== viewGeneration || disposed) return
    const historyText = termDecoder.decodeSnapshot(result.data, result.text)
    if (historyText) { term.appendLines(historyText); term.flushPending(); scanTpsChunk(historyText) }
    if (result.truncated) term.appendSystem('当前仅显示节点返回的日志尾部，完整日志请到文件管理查看。')
    consoleCursor.value = result.nextCursor ?? consoleCursor.value
    pollError.value = ''
  }
  catch (error) { if (epoch === viewGeneration) pollError.value = errorMessage(error, '日志读取失败') }
}

// ---------- 实时输出流（SSE：订阅节点 attach 泵，300ms 聚合推送） ----------

let outputOpens = 0
const outputConnected = ref(false)
let outputReady = false

const outputTransport = createSseTransport({
  onOpen: () => {
    termDecoder.reset()
    outputOpens += 1
    outputConnected.value = true
    pollError.value = ''
    if (outputOpens === 1) {
      term.appendSystem('[system] 实时输出已连接')
    }
    else {
      // 不用过期 cursor 盲拉补齐（会整段重放）：如实告知可能缺失。
      term.appendSystem('[system] 输出流已重连；中断期间的输出可能缺失')
    }
  },
  onFrame: (frame) => {
    const id = instanceId.value
    if (!id) {
      return
    }
    const event = unwrapStreamEvent(frame, {
      events: ['instance.output'],
      idKey: 'instanceId',
      expectedId: id,
      nodeId: streamNodeId || undefined,
    })
    if (!event) {
      return
    }
    if (event.gap) {
      const dropped = event.payload.dropped
      term.flushPending()
      term.appendSystem(`[system] 输出不连续：缓冲区丢弃了 ${dropped ?? '部分'} 个输出事件`)
      return
    }
    const payload = event.payload as { text?: unknown, data?: unknown }
    const chunk = termDecoder.decodeFrame(payload.data, payload.text)
    if (chunk.length) {
      term.appendLines(chunk)
      scanTpsChunk(chunk)
    }
  },
  onEnded: () => {
    // 服务端正常关闭：实例停止时 attach 流随容器退出结束，不重连（启动时会自动重开）。
    if (!running.value) {
      outputConnected.value = false
      term.appendSystem('[system] 实例已停止，实时输出流已关闭')
      return false
    }
    return true // 运行中被服务端关闭视为断线，交给退避重连
  },
  onError: (reason) => {
    outputConnected.value = false
    if (reason === 'auth') {
      pollError.value = '输出流鉴权失败（401/403），请重新登录后重试'
      outputTransport.stop()
      return
    }
    if (!running.value) {
      term.appendSystem('[system] 实例已停止，实时输出流已关闭')
      outputTransport.stop()
      return
    }
    pollError.value = '输出流中断，正在自动重连；中断期间的输出可能缺失'
  },
}, {
  getToken: () => localStorage.getItem('token'),
  idleTimeoutMs: 65_000,
})

function openOutputStream() {
  if (!instanceId.value || !canUse.value || !running.value || disposed) return
  outputTransport.start(extra.outputEventsUrl(instanceId.value))
}

/** 只断本地 SSE；不调用 outputUnsubscribe（实例级 attach 泵被全部订阅者共享）。 */
function closeOutputStream() {
  outputTransport.stop()
  outputConnected.value = false
}

/** 路由/实例切换与组件卸载共用的输出流复位。 */
function resetOutputStream() {
  closeOutputStream()
  outputOpens = 0
}

function startPlayersPolling() {
  stopPlayersPolling()
  playersTimer = setInterval(() => {
    if (running.value && !document.hidden) {
      void loadPlayers()
    }
  }, 10000)
}

function stopPlayersPolling() {
  if (playersTimer) {
    clearInterval(playersTimer)
    playersTimer = null
  }
}

async function sendCommand(command: string) {
  // 启动中（starting）服务端尚未就绪，命令会丢：只允许真正 running 时发送。
  if (!canUse.value || state.value !== 'running') { sendError.value = '实例未运行或当前账号没有操作权限'; return }
  const epoch = viewGeneration, id = instanceId.value
  const echoNo = term.appendCommandEcho(command)
  sendError.value = ''
  try {
    await withTimeout(extra.instanceCommand(id, command), 55_000, '命令发送结果未确认，请核对日志，不要重复发送')
    if (epoch === viewGeneration) term.commitCommand(command)
  }
  catch (error) {
    if (epoch !== viewGeneration) return
    sendError.value = errorMessage(error, '命令发送失败')
    term.markCommandFailed(echoNo, sendError.value)
  }
}

const acting = ref(false)

async function act(action: 'start' | 'stop' | 'restart' | 'kill') {
  if (acting.value || !instanceId.value || !canUse.value) return
  const id = instanceId.value, epoch = viewGeneration
  const run = async () => {
    if (acting.value || epoch !== viewGeneration) return
    acting.value = true
    try {
      const result = await withTimeout(extra.instanceAction(id, action), 65_000, '操作结果未确认，请刷新实例状态核对，不要重复提交') as Record<string, unknown>
      if (epoch !== viewGeneration) return
      if (result?.state) instance.value = { ...instance.value, ...result }
      sendError.value = ''
      toast.success('节点已确认操作，正在更新实例状态')
      await load()
      if (epoch !== viewGeneration) return
      if (running.value) openOutputStream()
      else { closeOutputStream(); term.flushPending() }
      void loadFiles()
    }
    catch (error) {
      if (epoch !== viewGeneration) return
      sendError.value = errorMessage(error, '操作失败')
      toast.error(sendError.value)
      void load()
    }
    finally { if (epoch === viewGeneration) acting.value = false }
  }
  if (action === 'kill' || action === 'restart') {
    modal.confirm({ title: action === 'kill' ? '强制终止实例' : '重启实例',
      content: `确认${action === 'kill' ? '强制终止' : '重启'}「${instance.value?.name ?? id}」？${action === 'kill' ? '未保存的服务器数据可能丢失。' : '在线玩家将暂时断开连接。'}`,
      confirmButtonText: action === 'kill' ? '强制终止' : '重启', onConfirm: run })
  }
  else await run()
}

async function loadBackups() {
  const id = instanceId.value, epoch = viewGeneration
  backupLoading.value = true
  try {
    const result = await withTimeout(extra.listBackups(id), 15_000, '备份列表读取超时') as {
      backups?: Array<Record<string, unknown>>
      creating?: boolean
      lastCreate?: { success?: boolean, error?: string, file?: string, finishedAt?: number } | null
    }
    if (epoch !== viewGeneration) return
    backupRows.value = (result.backups ?? []).map((item, index) => {
      const type = String(item.type ?? 'local')
      const file = String(item.file ?? '')
      const jobId = String(item.jobId ?? '')
      return {
        key: type === 'local' ? `local:${file}` : `job:${jobId || index}`,
        type: (type === 'local' || type === 'export' || type === 'offsite' ? type : 'local') as BackupRow['type'],
        file: file || undefined,
        size: item.size === undefined ? undefined : Number(item.size),
        at: Number(item.at ?? 0),
        jobId: jobId || undefined,
        status: item.status === undefined ? undefined : String(item.status),
        percent: item.percent === undefined ? undefined : Number(item.percent),
        message: item.message === undefined ? undefined : String(item.message),
        archiveName: item.archiveName === undefined ? undefined : String(item.archiveName),
        targetCode: item.targetCode === undefined ? undefined : String(item.targetCode),
        targetName: item.targetName === undefined ? undefined : String(item.targetName),
      }
    })
    backupCreating.value = Boolean(result.creating)
    backupLastCreate.value = result.lastCreate ?? null
  }
  catch {
    if (epoch === viewGeneration) {
      backupRows.value = []
      backupCreating.value = false
      backupLastCreate.value = null
    }
  }
  finally {
    if (epoch === viewGeneration) backupLoading.value = false
  }
}

const inputHint = computed(() => {
  if (sendError.value) {
    return sendError.value
  }
  if (state.value === 'starting' && canUse.value) {
    return '提示：实例启动中，等服务端就绪后再发送命令。'
  }
  if (state.value !== 'running' && canUse.value) {
    return '提示：实例未运行时无法向控制台发送命令，请先「启动」。'
  }
  return ''
})

/** 运行→停止的边沿：插入分隔行，明确区分「历史输出」与「仍在运行」。 */
watch(running, (now, was) => {
  if (now && outputReady) openOutputStream()
  if (was && !now) {
    closeOutputStream()
    term.flushPending()
    if (term.lines.value.length) term.appendDivider('实例已停止 · 以上为已接收输出')
  }
})

/** 实例状态轻量刷新：面板 DB 由节点上线钩子纠偏，页面低频跟进保持按钮/标签诚实。 */
let stateTimer: ReturnType<typeof setInterval> | null = null

async function refreshInstanceState() {
  if (document.hidden || !instanceId.value || loading.value || stateRefreshing || disposed) return
  const epoch = viewGeneration, id = instanceId.value
  stateRefreshing = true
  try {
    const result = await withTimeout(extra.instanceDetail(id), 12_000, '状态刷新超时') as Record<string, unknown>
    if (epoch === viewGeneration && !disposed) instance.value = result
  }
  catch { /* 保留上次快照，下一次轮询恢复。 */ }
  finally { if (epoch === viewGeneration) stateRefreshing = false }
}

/** 进入/切换实例的完整引导：先清旧状态，再按顺序拉取并交接实时流。 */
async function bootstrapInstance() {
  const epoch = ++viewGeneration
  outputReady = false
  stateRefreshing = false
  playersLoading.value = false
  acting.value = false
  term.busy.value = false
  term.commandText.value = ''
  // 1) 停旧流与旧订阅（不退订共享 attach 泵），清历史游标与视图。
  resetOutputStream()
  stopNodeStream()
  streamNodeId = ''
  instance.value = null
  consoleCursor.value = ''
  pollError.value = ''
  sendError.value = ''
  players.value = null
  backupRows.value = []
  liveCpuSeries.value = []
  liveMemSeries.value = []
  containerState.value = ''
  term.clear()
  term.reloadHistory()
  if (!instanceId.value) {
    loadError.value = '缺少实例 ID'
    return
  }
  // 2) 详情先行（需要 nodeId/state），随后备份、玩家、历史尾部补齐与性能历史。
  await load()
  if (epoch !== viewGeneration || disposed || loadError.value) return
  void loadBackups()
  void loadBackupPolicy()
  void loadPlayers()
  void loadMetricsHistory()
  void loadAuthlibInjection()
  void loadPlaytimeInjection()
  await pollOutput(true)
  if (epoch !== viewGeneration || disposed) return
  outputReady = true
  openOutputStream()
  syncInstallPolling()
}

onMounted(() => {
  void bootstrapInstance()
  startPlayersPolling()
  startTpsPolling()
  stateTimer = setInterval(() => void refreshInstanceState(), 10000)
})

watch(instanceId, (now, was) => {
  if (!was || now === was) {
    return
  }
  void bootstrapInstance()
})

onBeforeUnmount(() => {
  disposed = true
  viewGeneration++
  stopPlayersPolling()
  stopTpsPolling()
  stopBackupPolling()
  resetOutputStream()
  if (installTimer) {
    clearInterval(installTimer)
    installTimer = null
  }
  if (stateTimer) {
    clearInterval(stateTimer)
    stateTimer = null
  }
})
</script>

<template>
  <FaPageHeader :title="`控制台 · ${instance?.name ?? instanceId}`" class="mb-0">
    <FaButton variant="outline" @click="router.push(listPath)">
      返回列表
    </FaButton>
    <FaButton v-if="canUse" variant="outline" :disabled="acting || running" @click="act('start')">
      启动
    </FaButton>
    <FaButton v-if="canUse" variant="outline" :disabled="acting || !running" @click="act('stop')">
      停止
    </FaButton>
    <FaButton v-if="canUse" variant="outline" :disabled="acting" @click="act('restart')">
      重启
    </FaButton>
    <FaButton v-if="canUse" variant="destructive" :disabled="acting" @click="act('kill')">
      终止
    </FaButton>
    <FaButton variant="outline" @click="router.push(filesPath)">
      文件管理
    </FaButton>
  </FaPageHeader>

  <FaAlert v-if="loadError" variant="destructive" title="无法加载实例" class="mt-4">
  <template #description>
    {{ loadError }}
  </template>
  </FaAlert>

  <FaPageMain v-else v-loading="loading" class="mcp-mt-4" main-class="mcp-page-stack">
    <!-- 上传任务（异步分片上传，跨页面恢复显示） -->
    <UploadTasksPanel
      v-if="uploadTaskItems.length"
      :tasks="uploadTaskItems"
      :hashing="uploadHashing"
      :can-cancel="canManage"
      @cancel="taskId => void cancelUploadTask(taskId)"
    />

    <!-- 核心安装进度（创建后自动下载 / 安装文件的任务跟踪） -->
    <div v-if="activeInstall" class="rounded-xl border bg-card p-4">
      <div class="space-y-2">
        <div class="flex flex-wrap items-center gap-2 text-sm">
          <FaIcon name="i-ri:download-cloud-2-line" class="text-primary" />
          <span class="font-medium">正在安装 {{ activeInstall.fileName || '服务端文件' }}</span>
          <FaTag variant="secondary">
            {{ installPercent > 0 ? `${installPercent}%` : '进行中' }}
          </FaTag>
          <span v-if="installFailedCount > 0" class="text-xs text-destructive">
            失败 {{ installFailedCount }}
          </span>
          <span class="text-xs text-muted-foreground">
            {{ installPercent > 0 ? '按已下载字节实时更新（2 秒刷新）' : '节点未上报字节数（分块下载或旧版节点 0.3.1 及以下）' }}
          </span>
        </div>
        <div class="h-2 w-full overflow-hidden rounded-full bg-muted">
          <div
            class="h-full rounded-full bg-primary transition-all"
            :style="{ width: `${Math.max(installPercent, installPercent > 0 ? 3 : 0)}%` }"
          />
        </div>
        <!-- 文件级明细：节点 0.5.0+ 才上报 files 快照，旧节点整块隐藏维持原样 -->
        <div v-if="activeInstallFiles" class="space-y-1 pt-1 text-xs">
          <div class="text-muted-foreground">
            已完成 {{ installDoneCount }}/{{ installTotalCount }}
            <template v-if="installFailedCount > 0">· <span class="text-destructive">失败 {{ installFailedCount }}</span></template>
            · 正在下载 {{ installActiveRows.length }}
          </div>
          <div
            v-for="(file, index) in installActiveRows"
            :key="`a-${index}-${file.path}`"
            class="flex min-w-0 items-center gap-2"
          >
            <FaIcon name="i-ri:loader-4-line" class="is-spinning shrink-0 text-primary" />
            <span class="mcp-mono min-w-0 truncate" :title="file.path">{{ file.path }}</span>
            <span class="mcp-status-meta shrink-0">
              {{ formatBytes(Number(file.bytes ?? 0)) }}
              <template v-if="Number(file.total ?? 0) > 0"> / {{ formatBytes(Number(file.total)) }}</template>
            </span>
          </div>
          <div
            v-for="(file, index) in installFailedRows"
            :key="`f-${index}-${file.path}`"
            class="flex min-w-0 items-center gap-2"
          >
            <FaIcon name="i-ri:error-warning-line" class="shrink-0 text-destructive" />
            <span class="mcp-mono min-w-0 truncate" :title="file.path">{{ file.path }}</span>
            <span class="min-w-0 truncate text-destructive" :title="file.error">{{ file.error }}</span>
          </div>
          <div v-if="installHiddenFailed > 0" class="text-muted-foreground">
            另有 {{ installHiddenFailed }} 个失败文件未展开（节点最多保留前 50 条失败明细）
          </div>
        </div>
      </div>
    </div>

    <div v-else-if="recentInstall" class="rounded-xl border bg-card px-4 py-2 text-sm">
      <template v-if="recentInstall.state === 'done'">
        <FaIcon name="i-ri:check-double-line" class="mr-1 text-green-600" />
        {{ recentInstall.fileName || '服务端文件' }} 安装完成
      </template>
      <template v-else>
        <FaIcon name="i-ri:error-warning-line" class="mr-1 text-destructive" />
        {{ recentInstall.fileName || '服务端文件' }} 安装失败：{{ recentInstall.error || recentInstall.state }}
        <span v-if="recentFailedCount > 0" class="text-xs text-muted-foreground">（失败文件 {{ recentFailedCount }} 个，明细见节点日志）</span>
        <FaButton v-if="canManage" class="ml-2" size="sm" variant="outline" :loading="installRetrying" @click="retryInstall">
          重试安装
        </FaButton>
      </template>
    </div>

    <!-- 实时资源监控条：节点 SSE 容器维度统计 + 历史回填/页面内趋势采样 -->
    <div class="mcp-live-strip">
      <div class="mcp-metric">
        <div class="mcp-metric-head">
          <span>CPU</span>
          <strong>{{ liveCpuNow }}</strong>
        </div>
        <MetricTrendChart :values="liveCpuSeries" :height="34" :max="100" name="CPU" />
      </div>
      <div class="mcp-metric">
        <div class="mcp-metric-head">
          <span>内存</span>
          <strong>{{ liveMemNow }}</strong>
        </div>
        <MetricTrendChart :values="liveMemSeries" :height="34" :max="100" name="内存" />
      </div>
      <div class="mcp-metric">
        <div class="mcp-metric-head">
          <span>容器</span>
          <strong>{{ containerState || stateLabel }}</strong>
        </div>
        <div class="flex items-center gap-2">
          <div class="flex rounded-lg border">
            <button
              v-for="win in metricWindows"
              :key="win.value"
              type="button"
              class="px-2 py-0.5 text-xs transition-colors"
              :class="metricWindow === win.value ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
              :disabled="metricLoading"
              @click="selectMetricWindow(win.value)"
            >
              {{ win.label }}
            </button>
          </div>
          <span class="mcp-status-meta">节点快照 {{ relativeSeenText(latestStats?.reportedAt) }}</span>
        </div>
      </div>
    </div>

    <TerminalConsole
      :key="instanceId"
      :term="term"
      mode="mc"
      :can-use="canUse && running && !acting"
      :transport="{ connected: outputConnected }"
      :empty-text="running ? '等待控制台输出…' : `实例状态：${stateLabel}。启动后可接收日志并发送命令。`"
      :hint="inputHint"
    >
      <template #tags>
        <FaTag variant="default">
          {{ instance?.name ?? instanceId }}
        </FaTag>
        <FaTag :variant="running ? 'default' : 'secondary'">
          {{ stateLabel }}
        </FaTag>
        <FaTag variant="outline">
          {{ kindLabel }}
        </FaTag>
        <FaTag variant="secondary">
          {{ instance?.mcVersion || '-' }}
        </FaTag>
        <FaTag v-if="tps != null" variant="outline" class="mcp-tps-tag" :class="tps >= 19 ? 'is-good' : tps >= 15 ? 'is-warn' : 'is-bad'" title="TPS from last 1m（Paper 系自动探测）">
          TPS {{ tps.toFixed(1) }}
        </FaTag>
        <FaTag v-if="gamePort?.hostPort" variant="outline">
          端口 :{{ gamePort.hostPort }}
        </FaTag>
      </template>

      <template #above-screen>
        <div v-if="!running && term.lines.value.length" class="mcp-wt-history-tag">
          历史输出 · 实例未运行
        </div>
      </template>

      <template #left>
        <FileBrowserPanel
          ref="browserRef"
          :key="instanceId"
          title="文件"
          :list="browserList"
          :open-file="openFileFromBrowser"
        />
        <button type="button" class="mcp-wt-btn mcp-wt-mt" @click="router.push(filesPath)">
          打开完整文件管理 →
        </button>
      </template>

      <template #right>
        <div class="mcp-wt-pane-title">
          在线玩家
        </div>
        <div class="mcp-term-players">
          <template v-if="players?.reachable">
            <span class="mcp-wt-muted" style="padding:2px 0;">
              {{ players.online ?? 0 }}/{{ players.max ?? '?' }}
              <template v-if="players.latencyMs != null"> · {{ players.latencyMs }}ms</template>
            </span>
            <button v-for="name in playerNames" :key="name" type="button" class="mcp-term-player is-clickable" :title="`插入「${name}」到命令框`" @click="fillCommandWord(name)">{{ name }}</button>
            <span v-if="!playerNames.length && players.online" class="mcp-wt-muted">
              {{ players.online }} 名玩家在线（服务端未上报名单）
            </span>
          </template>
          <span v-else class="mcp-wt-muted" style="padding:2px 0;">
            {{ running ? (players?.reason || '探测中…') : '实例未运行' }}
          </span>
        </div>

        <div class="mcp-wt-pane-title">
          常用命令
        </div>
        <div class="mcp-wt-list">
          <button v-for="cmd in COMMON_COMMANDS" :key="cmd" type="button" class="mcp-wt-cmd" @click="term.commandText.value = cmd">
            {{ cmd }}
          </button>
        </div>
        <div class="mcp-wt-pane-title">
          历史命令
        </div>
        <div class="mcp-wt-list">
          <button
            v-for="(cmd, index) in [...term.history.value].reverse().slice(0, 15)"
            :key="`${cmd}-${index}`"
            type="button"
            class="mcp-wt-cmd is-hist"
            @click="term.commandText.value = cmd"
          >
            {{ cmd }}
          </button>
          <div v-if="!term.history.value.length" class="mcp-wt-muted">
            暂无
          </div>
        </div>
      </template>

      <template #status-start>
        <span>状态 {{ stateLabel }}</span>
        <span v-if="pollError" class="is-warn">输出：{{ pollError }}</span>
      </template>
    </TerminalConsole>

    <div v-if="pollError && pollError.includes('instance.notFound')" class="mcp-page-gap">
      <FaAlert variant="destructive" title="节点上没有该实例">
  <template #description>
        面板记录仍在，但节点容器/数据目录不存在。可：启动一次尝试重建容器；或删除实例后用引导式/ZIP 重新创建。
        <div class="mcp-row" style="margin-top:8px;">
          <FaButton size="sm" :disabled="acting" @click="act('start')">尝试启动</FaButton>
          <FaButton size="sm" variant="destructive" :disabled="acting" @click="act('kill')">终止</FaButton>
          <FaButton size="sm" variant="outline" @click="router.push(listPath)">返回列表删除重建</FaButton>
        </div>
  </template>
      </FaAlert>
    </div>

    <div class="mcp-fn-grid mcp-page-gap">
      <button type="button" class="mcp-fn-card" @click="router.push(filesPath)">
        <div class="mcp-fn-title">文件管理 / SFTP</div>
        <div class="mcp-fn-desc">数据目录、上传下载、临时 SFTP</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/schedules`)">
        <div class="mcp-fn-title">计划任务</div>
        <div class="mcp-fn-desc">间隔 / 每日定时控制台命令</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/server-config`)">
        <div class="mcp-fn-title">服务端配置</div>
        <div class="mcp-fn-desc">server.properties 结构化编辑</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/mods`)">
        <div class="mcp-fn-title">模组 / 插件</div>
        <div class="mcp-fn-desc">Modrinth 搜索安装到 plugins/mods</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/settings`)">
        <div class="mcp-fn-title">实例设置</div>
        <div class="mcp-fn-desc">命令、环境变量、RCON / Ping / 终端</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/proxy`)">
        <div class="mcp-fn-title">代理与子服</div>
        <div class="mcp-fn-desc">Velocity / Bungee 纳管、子服绑定与子服同步</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(`${listPath}/${instanceId}/domain`)">
        <div class="mcp-fn-title">实例域名</div>
        <div class="mcp-fn-desc">自动写入 A / SRV 解析，玩家免端口直连</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
      <button type="button" class="mcp-fn-card" @click="router.push(listPath)">
        <div class="mcp-fn-title">返回实例列表</div>
        <div class="mcp-fn-desc">卡片列表与节点筛选</div>
        <div class="mcp-fn-link">前往 →</div>
      </button>
    </div>

    <div class="mcp-wt-lower mcp-page-gap">
      <FaCard id="inst-info" title="基本信息">
        <FaDescriptions :items="basicItems" :column="2" border />
      </FaCard>
      <FaCard id="inst-authlib" title="Authlib 注入">
        <template v-if="authlibInjection">
          <FaAlert v-if="!authlibInjection.supported" variant="default" title="注入暂不可用">
            <template #description>{{ authlibInjection.reason }}</template>
          </FaAlert>
          <div v-else class="mcp-toolbar-row">
            <FaButton
              size="sm"
              :variant="authlibInjection.enabled ? 'outline' : 'default'"
              :loading="authlibBusy"
              :disabled="!canManage || authlibInjection.running"
              @click="toggleAuthlibInjection(!authlibInjection.enabled)"
            >
              {{ authlibInjection.enabled ? '关闭注入' : '开启注入' }}
            </FaButton>
            <div class="min-w-0">
              <p class="text-sm font-medium">
                {{ authlibInjection.enabled ? '已注入：启动命令含 -javaagent，启动实例后生效' : '未注入' }}
              </p>
              <p class="truncate text-xs text-muted-foreground">验证服 API：{{ authlibInjection.apiRoot || '-' }}</p>
              <p v-if="authlibInjection.running" class="text-xs text-muted-foreground">
                实例运行中：停止后才能切换注入（与修改配置一致）。
              </p>
            </div>
          </div>
        </template>
        <p v-else class="text-sm text-muted-foreground">注入能力不可用（面板设置未配置或无查看权限）。</p>
      </FaCard>
      <FaCard id="inst-playtime" title="在线时长注入">
        <template v-if="playtimeInjection">
          <FaAlert v-if="!playtimeInjection.supported" variant="default" title="注入暂不可用">
            <template #description>{{ playtimeInjection.reason }}</template>
          </FaAlert>
          <div v-else class="mcp-toolbar-row">
            <FaButton
              size="sm"
              :variant="playtimeInjection.enabled ? 'outline' : 'default'"
              :loading="playtimeBusy"
              :disabled="!canManage || playtimeInjection.running"
              @click="togglePlaytimeInjection(!playtimeInjection.enabled)"
            >
              {{ playtimeInjection.enabled ? '关闭注入' : '开启注入' }}
            </FaButton>
            <div class="min-w-0">
              <p class="text-sm font-medium">
                {{ playtimeInjection.enabled
                  ? `已注入：制品在 ${playtimeInjection.dir}/ 目录，启动实例后生效`
                  : '未注入' }}
              </p>
              <p class="truncate text-xs text-muted-foreground">
                匹配制品：{{ playtimeInjection.matchedName || '-' }}
              </p>
              <p v-if="playtimeInjection.running" class="text-xs text-muted-foreground">
                实例运行中：停止后才能切换注入。
              </p>
            </div>
          </div>
        </template>
        <p v-else class="text-sm text-muted-foreground">注入能力不可用（面板设置未配置制品矩阵或无查看权限）。</p>
      </FaCard>
      <FaCard id="inst-backup" title="备份">
        <div class="mcp-toolbar-row mb-3">
          <span class="mcp-status-meta flex items-center gap-2">
            保留：最多
            <FaInput v-model="policyKeepCount" type="number" class="w-20" />
            份 /
            <FaInput v-model="policyKeepDays" type="number" class="w-20" />
            天（0=不限）
            <FaButton size="sm" variant="outline" :disabled="!canManage" :loading="policySaving" @click="saveBackupPolicy">
              保存策略
            </FaButton>
          </span>
          <div class="mcp-spacer mcp-row">
            <FaButton v-if="canManage" size="sm" @click="openBackupModal">
              创建备份
            </FaButton>
            <FaButton size="sm" variant="outline" @click="() => { void loadBackups() }">
              刷新
            </FaButton>
          </div>
        </div>
        <p v-if="backupCreating" class="mb-2 text-sm text-muted-foreground">
          正在打包备份，完成后自动出现在列表（大世界可能需要数分钟）…
        </p>
        <FaTable
          v-loading="backupLoading"
          :columns="backupColumns"
          :data="backupRows"
          row-key="key"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[720px]"
          border
          stripe
          empty-text="暂无备份"
        >
          <template #cell-kind="{ row }">
            <FaTag :variant="row.original.type === 'local' ? 'default' : 'secondary'">
              {{ backupKindLabel(row.original) }}
            </FaTag>
          </template>
          <template #cell-file="{ row }">
            <span v-if="row.original.type === 'local'" class="mcp-mono break-all">{{ row.original.file }}</span>
            <span v-else class="mcp-mono break-all">{{ row.original.archiveName || `备份任务 #${row.original.jobId ?? '-'}` }}</span>
          </template>
          <template #cell-size="{ row }">
            {{ row.original.type === 'local' && row.original.size !== undefined ? formatBytes(row.original.size) : '-' }}
          </template>
          <template #cell-status="{ row }">
            <span v-if="row.original.type !== 'local'" :title="row.original.message || ''"
                  :class="String(row.original.status) === 'FAILED' ? 'text-destructive' : ''">
              {{ backupStatusLabel(row.original) || '-' }}
            </span>
            <span v-else>-</span>
          </template>
          <template #cell-at="{ row }">
            {{ formatDateTime(row.original.at as number) }}
          </template>
          <template #cell-operation="{ row }">
            <div v-if="row.original.type === 'local' && canManage" class="mcp-op-cell">
              <FaButton
                size="sm"
                variant="outline"
                :loading="restoringFile === String(row.original.file)"
                @click="confirmRestoreBackup(row.original)"
              >
                恢复
              </FaButton>
              <FaButton
                size="sm"
                variant="destructive"
                :loading="deletingFile === String(row.original.file)"
                @click="confirmDeleteBackup(row.original)"
              >
                删除
              </FaButton>
            </div>
            <span v-else>-</span>
          </template>
        </FaTable>
      </FaCard>
    </div>
    <FileEditorModal
      v-model="editorOpen"
      title="实例文件编辑"
      :load="editorAccess.load"
      :save="editorAccess.save"
      :list-dir="browserList"
      :can-save="canManage"
      :initial-path="editorPath"
      :initial-directory="editorDirectory"
      @saved="() => browserRef?.reload()"
    />
    <FaModal
      v-model="backupModalOpen"
      title="创建备份"
      :show-cancel-button="true"
      confirm-button-text="开始备份"
      :confirm-button-loading="backupSubmitting"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitBackup(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">备份位置</span>
          <FaSelect
            v-model="backupTarget"
            :options="[
              { label: '本地（节点磁盘，可在本页恢复）', value: 'local' },
              { label: '异地（宿主数据备份中心推送）', value: 'offsite' },
            ]"
            class="mcp-w-full"
          />
          <span class="mcp-form-hint">本地备份保存在节点备份目录；异地备份经「数据备份中心」打包并推送到目标存储。</span>
        </label>
        <label v-if="backupTarget === 'offsite'" class="mcp-form-item">
          <span class="mcp-form-label">异地目标</span>
          <FaSelect v-if="backupTargets.catalogReady.value" v-model="backupTargetCode" :options="backupTargetOptions" class="mcp-w-full" />
          <template v-else>
            <FaInput v-model="backupTargetCode" class="mcp-w-full mcp-mono" placeholder="nas-webdav" />
            <span class="mcp-form-hint">无法读取目标目录（宿主 SDK 过旧或暂无启用目标），请手输目标编码。</span>
          </template>
        </label>
        <FaAlert v-if="backupModalError" variant="destructive" title="无法提交">
          <template #description>
            {{ backupModalError }}
          </template>
        </FaAlert>
      </div>
    </FaModal>
  </FaPageMain>
</template>
