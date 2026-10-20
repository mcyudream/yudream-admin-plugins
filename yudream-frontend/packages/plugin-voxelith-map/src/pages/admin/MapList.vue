<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, TableColumn, ActionMenuItem } from '@yudream/components'
import type { ClientJarEntry } from '../../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdCard, YdFileUpload, YdIcon, YdInput, YdModal, YdNumberField, YdPagination, YdProgress, YdSelect, YdSwitch, YdTag, useYdModal, useYdToast, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  DIMENSION_OPTIONS,
  dimensionLabel,
  formatError,
  formatTime,
  MAP_STATE_META,
  useVoxelithAdmin,
} from '../../composables/useVoxelithAdmin'
import { createVoxelithMapAdminApi } from '../../api/voxelith-map-admin-api'
import { dimensionFromArchiveName } from '../../composables/dimensionFromName'
import { groupMapsByArchive } from '../../composables/archiveGroups'
import { entryNameOf, instanceDimensions, listInstanceDir, mcpanelJson } from '../../composables/mcpanel-world'
import { useModPackInspect } from '../../composables/useModPackInspect'
import {
  browserImportState,
  cancelBrowserImport,
  isBrowserImportActive,
  runBrowserImport,
} from '../../composables/useBrowserImport'
import { renderTaskProgress } from '../../composables/useRenderTaskProgress'
import type { MapAdmin, MapAdminState } from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const modal = useYdModal()
const model = useVoxelithAdmin(props.sdk)
const toast = useYdToast()

// ---------------------------------------------------------------------------
// 原版客户端 jar 库：预上传各版本，新建地图时按版本直接选用
// ---------------------------------------------------------------------------

const adminApi = createVoxelithMapAdminApi(props.sdk)
/** 新建弹窗里的模组包：选中即上传 + 解析 */
const packInspect = useModPackInspect(props.sdk)
const api = adminApi
const jarLibraryOpen = ref(false)
const jarList = ref<ClientJarEntry[]>([])
const jarLoading = ref(false)
const newJarVersion = ref('')
const newJarFiles = ref<FileItem[]>([])
const newJarFile = ref<File | null>(null)
const newJarUploading = ref(false)

function isJarUpload(file: File): boolean {
  return file.name.toLowerCase().endsWith('.jar')
}

/** 与后端 MinecraftVersions.versionOf 同式的文件名版本识别（第一个 1.x[.y] 片段） */
function versionOfFileName(fileName: string): string | null {
  const matched = /1\.\d+(?:\.\d+)?/.exec(fileName)
  return matched ? matched[0] : null
}

function selectNewJar(options: FileUploadRequestOptions): { selected: boolean } {
  newJarFiles.value = [options.file as unknown as FileItem]
  newJarFile.value = options.file
  // 从文件名自动识别版本号（如 client-1.21.8.jar → 1.21.8），识别结果直接回填输入框
  const detected = versionOfFileName(options.file.name)
  if (detected) newJarVersion.value = detected
  options.onProgress(100)
  return { selected: true }
}

async function openJarLibrary(): Promise<void> {
  jarLibraryOpen.value = true
  await refreshJarLibrary()
}

async function refreshJarLibrary(): Promise<void> {
  jarLoading.value = true
  try {
    const res = await adminApi.clientJars()
    jarList.value = res.jars ?? []
  }
  catch (e) {
    toast.error(formatError(e, '客户端 jar 库加载失败'))
  }
  finally {
    jarLoading.value = false
  }
}

function mb(sizeBytes: number): string {
  return sizeBytes > 0 ? `${(sizeBytes / 1024 / 1024).toFixed(1)}MiB` : '—'
}

async function uploadJarToLibrary(): Promise<void> {
  const version = newJarVersion.value.trim()
  const file = newJarFile.value
  if (!version || !file) {
    toast.warning('请填写版本号并选择 jar 文件')
    return
  }
  newJarUploading.value = true
  try {
    const fileId = await props.sdk.files.uploadImage(file, { module: 'voxelith-map', publicAccess: false })
    if (!fileId?.id) throw new Error('文件上传后未返回 id')
    await adminApi.addClientJar({ version, fileId: fileId.id, fileName: file.name, sizeBytes: file.size })
    toast.success(`已添加客户端 jar ${version}`)
    newJarVersion.value = ''
    newJarFiles.value = []
    newJarFile.value = null
    await refreshJarLibrary()
  }
  catch (e) {
    toast.error(formatError(e, '添加客户端 jar 失败'))
  }
  finally {
    newJarUploading.value = false
  }
}

function removeJarFromLibrary(entry: ClientJarEntry): void {
  modal.confirm({
    title: '删除客户端 jar',
    content: `确认从 jar 库删除版本「${entry.version}」吗？已创建的地图不受影响。`,
    onConfirm: () => {
      void adminApi.removeClientJar(entry.version).then(async () => {
        toast.success(`已删除 ${entry.version}`)
        await refreshJarLibrary()
      }).catch((e: unknown) => toast.error(formatError(e, '删除失败')))
    },
  })
}

// --- 从 MC 面板实例快捷导入 ---
const panelInstances = ref<{ id: string, name: string, mcVersion: string, state: string }[]>([])
const panelAvailable = ref(false)

const panelInstanceChoice = ref('')

// mcpanel 既有 HTTP 端点直连（浏览器管理员会话自带鉴权；不改 mcpanel 代码）

const MCP = '/api/plugins/mcpanel/admin'

/**
 * 面板元数据探测的超时上限。软依赖未装/面板卡住时，探测请求本来会一直挂在浏览器里占掉一条
 * 同源连接（HTTP/1.1 只有约 6 条），把插件自己的 API 请求一起拖住——刷新页面就表现为「无响应」。
 */
const PANEL_PROBE_TIMEOUT_MS = 15_000

/** 探测实例列表（挂载探测与聚焦/切回复查共用；见下方 refreshPanelInstances 的合并逻辑） */
async function probePanelInstances(): Promise<void> {
  try {
    const data = await mcpanelJson(`${MCP}/instances?page=1&size=100`, undefined, PANEL_PROBE_TIMEOUT_MS)
    // 仅当响应确实是 mcpanel 的实例列表（含 records 数组）才视为可用：
    // mcpanel 停用/未安装时端点 404，或被宿主包装成非实例响应，都必须隐藏入口
    const records = (data.records ?? []) as Array<{ id: string, name: string, mcVersion?: string, state?: string }>
    if (!Array.isArray(data.records)) {
      throw new Error('mcpanel 实例端点不可用')
    }
    panelInstances.value = records.map(r => ({
      id: String(r.id),
      name: String(r.name ?? r.id),
      mcVersion: String(r.mcVersion ?? ''),
      state: String(r.state ?? ''),
    }))
    panelAvailable.value = true
  }
  catch {
    panelInstances.value = []
    panelAvailable.value = false
  }
}

/**
 * 探测 mcpanel 可用性（软依赖，可选）：决定「从实例导入」入口显隐。
 *
 * <p>同一次加载里挂载、窗口聚焦与页面切回可见会几乎同时到达，三者必须合并成一次请求——
 * 此前是三发独立请求，一屏管理页光探测就打出三次相同调用（每次 5KB+ 响应），
 * 叠加每 2.5 秒的任务轮询与封面取图，同源连接很快被占满。</p>
 */
let panelProbe: Promise<void> | null = null
function refreshPanelInstances(): Promise<void> {
  panelProbe ??= probePanelInstances().finally(() => {
    panelProbe = null
  })
  return panelProbe
}

// 回到本页（含从其他插件页切回）时复查一次，mcpanel 启停后入口随之出现/隐藏。
// 监听器必须随组件卸载摘除：此前注册在 setup 顶层且用匿名函数，永远摘不掉——
// 每次进入本页都会多留一对永久监听器，刷新后被 focus/visibilitychange 反复触发。
// 同时复查服务端导入能力：mcpanel 启停/升级后搬运方式随之切换。
function recheckPanel(): void {
  void refreshPanelInstances().catch(() => {})
  void refreshImportCapability()
}

function onPanelVisibilityChange(): void {
  if (!document.hidden) recheckPanel()
}

onMounted(() => {
  recheckPanel()
  window.addEventListener('focus', recheckPanel)
  document.addEventListener('visibilitychange', onPanelVisibilityChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', recheckPanel)
  document.removeEventListener('visibilitychange', onPanelVisibilityChange)
  // 在途探测随本页作废：其结果只用于本页入口显隐，不必等它回来
  panelProbe = null
})

/** 与后端 MinecraftVersions.versionOf 同式的文件名版本识别 */
function versionOfJarName(fileName: string): string | null {
  const matched = /1\.\d+(?:\.\d+)?/.exec(fileName)
  return matched ? matched[0] : null
}

/** 实例选定后：列出根目录，按文件名判定存档版本（优先服务端核心命名，如 server/fabric/forge），
 * 同时探测要导入的维度（新版 world/world_nether/world_the_end 与老版 world 里的 DIM-1/DIM1）。 */
async function detectVersionFromRootJars(instanceId: string): Promise<void> {
  detectedVersion.value = ''
  detectedVersionFrom.value = ''
  detectedWorldDirs.value = []
  try {
    const entries = await listInstanceDir(instanceId, '.')
    const dimensions = instanceDimensions(entries)
    // 老版存档的下界/末地在 world 里面：只看根目录会漏，再探一层（失败就当没有）
    if (!dimensions.some(dir => dir.dimension !== 'minecraft:overworld')) {
      try {
        const worldEntries = await listInstanceDir(instanceId, dimensions[0]?.packPath ?? 'world')
        detectedWorldDirs.value = instanceDimensions(entries, worldEntries).map(dir => dir.rootName)
      }
      catch {
        detectedWorldDirs.value = dimensions.map(dir => dir.rootName)
      }
    }
    else {
      detectedWorldDirs.value = dimensions.map(dir => dir.rootName)
    }
    const jars = entries
      .map(entryNameOf)
      .filter(name => name.toLowerCase().endsWith('.jar'))
      .map(name => ({ name, version: versionOfJarName(name) }))
      .filter(e => e.version !== null)
    if (!jars.length) return
    const preferred = jars.find(e => /server|fabric|forge|paper|purpur|spigot|mohist|arclight|vanilla/i.test(e.name))
      ?? jars[0]
    detectedVersion.value = preferred.version as string
    detectedVersionFrom.value = preferred.name
  }
  catch {
    // 列目录失败：版本留空（导入时按自动判定处理），维度也留空——
    // 「没探测到」与「探测到只有主世界」是两件事，前者交给搬运前重探/服务端自行探测
    detectedWorldDirs.value = []
  }
}

watch(panelInstanceChoice, (id) => {
  detectedVersion.value = ''
  detectedVersionFrom.value = ''
  detectedWorldDirs.value = []
  if (id) void detectVersionFromRootJars(id)
})

const importOpen = ref(false)
const importName = ref('')
/** 是否同步导入实例 mods 目录下的模组 jar */
const importMods = ref(true)
/** 从实例根目录 jar 文件名识别出的存档版本（不读 level.dat） */
const detectedVersion = ref('')
const detectedVersionFrom = ref('')
/** 实例根目录里探测到的维度目录（每个建一张地图）；空 = 没探测出来（浏览器搬运会在打包前重探） */
const detectedWorldDirs = ref<string[]>([])
/** 提交进行中（弹窗按钮与入口的防重入守卫；搬运状态在 useBrowserImport 里，不随本页卸载消失） */
const importing = ref(false)
/** 服务端能否直接读取实例文件：面板插件提供契约时为真，否则交给浏览器搬运 */
const serverImport = ref(false)
/** 浏览器搬运的应用级状态：浮层直接读它，页面切换不丢 */
const browserImport = browserImportState()

function openImportDialog(): void {
  importName.value = ''
  panelInstanceChoice.value = ''
  importOpen.value = true
  void refreshPanelInstances()
  void refreshImportCapability()
}

/**
 * 探测服务端导入能力。
 *
 * <p>面板插件没装/没启用，或装的是**没有实例文件访问契约的旧版（如 0.22.1）**时，服务端读不到
 * 实例文件——此时自动改走浏览器搬运（面板插件无需升级），导入照样登记成任务。</p>
 */
async function refreshImportCapability(): Promise<void> {
  try {
    const res = await adminApi.instanceImportCapability()
    serverImport.value = res.serverImport === true
  }
  catch {
    serverImport.value = false
  }
}

/** 把 mcpanel 的原始报错翻译成可操作的提示（账号缺面板权限/实例未停止等） */
function friendlyMcpanelError(e: unknown): string {
  const message = e instanceof Error ? e.message : String(e)
  if (message.toLowerCase().includes('permission denied')) {
    // 节点进程读不了实例文件：文件属主与节点运行用户不一致（容器常以 root 写盘）
    return '节点进程无权读取实例文件（文件属主与面板节点运行用户不一致）：'
      + '请在节点主机上修正实例目录权限（如 chown -R <节点用户> 或 chmod -R a+rX <实例目录>），'
      + '或让面板节点以与实例文件属主一致的用户运行后重试'
  }
  if (message.includes('无权限') || message.includes('403') || message.includes('401')) {
    return '当前账号没有 MC 面板插件的访问权限：请在宿主角色管理中为该账号授予「MC 面板」插件权限后重试'
  }
  if (message.includes('not running') || message.includes('未运行')) {
    return '实例未运行或容器已退出：部分面板节点仅允许在实例运行时访问文件，请先启动实例再导入'
  }
  return message
}

/**
 * 提交实例导入。
 *
 * <p>面板插件提供实例文件访问契约时走**服务端作业**：提交即关窗，搬运在插件进程内执行，刷新或
 * 关闭页面都不会中断，进度、日志与取消都在任务中心。</p>
 *
 * <p>没有该契约（旧版面板）时走**浏览器搬运**：登记任务 → 本页（应用级状态，见
 * {@code composables/useBrowserImport}）搬运并把进展回报进任务 → 存档上传后交服务端建图。
 * 搬运状态不挂在页面组件上，因此在宿主 SPA 内切到别的插件页不会中断它；只有真正的页面关闭/刷新
 * 才会中断（那时用 keepalive 把任务落为失败并写明原因）。</p>
 */
async function confirmImport(): Promise<void> {
  if (!panelInstanceChoice.value || importing.value) return
  if (isBrowserImportActive()) {
    // 搬运状态在应用级：从别的页面切回来时这里可能是新一轮点击，别让两次搬运互相抢任务
    toast.info('已有一次实例搬运在进行中，请等它结束或在任务中心取消后再发起')
    return
  }
  const instanceId = panelInstanceChoice.value
  const inst = panelInstances.value.find(i => i.id === instanceId)
  const instLabel = inst?.name || instanceId
  // 关窗后表单不可见，取值先落成局部常量（后续 await 期间不再读表单状态）
  const payload = {
    instanceId,
    instanceName: instLabel,
    name: importName.value.trim() || inst?.name || '',
    version: detectedVersion.value,
    withMods: importMods.value,
    // 维度目录清单：探测到了就带上（服务端作业在建图时用它、浏览器搬运在打包前也会重探），
    // 没探测到就留空——留空表示「未知」，不要用 main 世界的兜底值把「未知」伪装成「只有主世界」
    worldDirs: detectedWorldDirs.value.length ? [...detectedWorldDirs.value] : undefined,
  }

  importing.value = true
  try {
    if (serverImport.value) {
      await adminApi.startImportJob(payload)
      // 提交即关窗：任务是服务端的，本页不再持有任何进度状态
      importOpen.value = false
      toast.success(`已提交「${instLabel}」的导入任务，进度与日志见任务中心`)
      // 新建的任务经任务流推送进来；任务结束时同一通道会刷新地图列表
      await model.loadMaps(true)
      return
    }
    // 浏览器搬运：关窗即开始搬运，浮层由应用级状态驱动，页面切换不影响它
    importOpen.value = false
    await model.loadMaps(true)
    await runBrowserImport(props.sdk, payload, file => model.uploadFile(file))
  }
  catch (e) {
    // 具体原因已由搬运逻辑写进任务并 toast 过；这里只兜住启动阶段的意外
    if (!isBrowserImportActive()) {
      toast.error(friendlyMcpanelError(e), { duration: 6000 })
    }
  }
  finally {
    importing.value = false
    await model.loadMaps(true)
  }
}

function openTaskCenter(): void {
  void router.push('/platform/plugins/voxelith-map/tasks')
}


const jarLibraryOptions = computed(() => [
  { label: '不上传（渲染时按存档版本自动下载）', value: 'auto' },
  ...jarList.value.map(j => ({ label: `${j.version}（${mb(j.sizeBytes)}）`, value: j.version })),
  { label: '上传新版本…', value: 'upload' },
])

const clientJarChoice = ref('auto')

watch(clientJarChoice, (choice) => {
  const entry = jarList.value.find(j => j.version === choice)
  model.clientJarPreset.value = entry ? { fileId: entry.fileId, name: entry.fileName } : null
  if (choice !== 'upload') clientJarFiles.value = []
})

// 导入任务与弹窗解耦：点击「导入并创建」即关窗，搬运在后台继续，
// 关窗只是收起了表单，不代表取消任务（进度见页面右下角浮层，取消走浮层按钮）。

// 打开创建弹窗时刷新库列表并重置选择
watch(() => model.createOpen.value, async (open) => {
  if (!open) return
  clientJarChoice.value = 'auto'
  clientJarFiles.value = []
  model.clientJarPreset.value = null
  void refreshPanelInstances()
  try {
    const res = await adminApi.clientJars()
    jarList.value = res.jars ?? []
  }
  catch {
    // 库列表加载失败不影响创建（仍可选上传/自动下载）
  }
})

const columns: TableColumn<ArchiveRow>[] = [
  { id: 'name', header: '存档名称', width: 200, fixed: 'left' },
  { id: 'dimension', header: '维度', width: 120 },
  { id: 'state', header: '状态', width: 120 },
  { id: 'scale', header: '规模描述', width: 220 },
  { id: 'updatedAt', header: '更新时间', width: 160 },
]

const worldFiles = ref<FileItem[]>([])
const clientJarFiles = ref<FileItem[]>([])
const modJarItems = ref<FileItem[]>([])
/** 从存档包名自动判定出的维度（展示用；空 = 未识别，保留手动选择） */
const detectedWorldDimension = ref('')

function isZipFile(file: File): boolean {
  return file.type === 'application/zip' || file.name.toLowerCase().endsWith('.zip')
}

function isJarFile(file: File): boolean {
  return file.type === 'application/java-archive' || file.name.toLowerCase().endsWith('.jar')
}

/** 模组文件：单个 jar，或整个 mods 目录的 zip 包（后端解包逐个落库） */
function isModFile(file: File): boolean {
  return isJarFile(file) || isZipFile(file)
}

function selectWorldFile(options: FileUploadRequestOptions) {
  model.worldFile.value = options.file
  // 存档各维度各自对应一张地图：按包名自动判定维度并回填选择器，判不出则保留管理员的选择
  const detected = dimensionFromArchiveName(options.file.name)
  if (detected) {
    model.createForm.dimension = detected
    detectedWorldDimension.value = detected
  }
  else {
    detectedWorldDimension.value = ''
  }
  options.onProgress(100)
  return { selected: true }
}

function selectClientJar(options: FileUploadRequestOptions) {
  model.clientJarFile.value = options.file
  options.onProgress(100)
  return { selected: true }
}

function selectModJar(options: FileUploadRequestOptions) {
  model.modJarFiles.value = [...model.modJarFiles.value, options.file]
  // zip 包选中即上传并解析：创建前就能看到包内有哪些模组 jar；提交时复用这次上传
  if (isZipFile(options.file)) {
    void uploadAndInspectModPack(options.file)
  }
  options.onProgress(100)
  return { selected: true }
}

/** 立即上传 zip 并解析包内 jar（弹窗内展示），同时登记给提交流程复用。 */
async function uploadAndInspectModPack(file: File): Promise<void> {
  const fileId = await packInspect.uploadAndInspect(file)
  if (fileId) {
    model.modsPackPreset.value = { fileId, name: file.name }
  }
  else {
    model.modsPackPreset.value = null
  }
}

// 组件内移除某个文件时，把待上传模组按名字对齐删掉（上传列表与待传 File[] 保持同序）
watch(modJarItems, (items) => {
  if (items.length === model.modJarFiles.value.length) return
  const names = new Set(items.map(item => item.name))
  model.modJarFiles.value = model.modJarFiles.value.filter(file => names.has(file.name))
  // 被移除的那个若正是已预上传的包，引用与解析结果一并作废
  if (model.modsPackPreset.value && !names.has(model.modsPackPreset.value.name)) {
    model.modsPackPreset.value = null
    packInspect.clear()
  }
})

// 每次打开新建弹窗都清空上一次的模组选择（composable.openCreate 只重置 File[]）
watch(() => model.createOpen.value, (open) => {
  if (open) {
    modJarItems.value = []
    packInspect.clear()
  }
})

function openViewer(map: MapAdmin): void {
  window.open(`/platform/plugins/voxelith-map/viewer?map=${encodeURIComponent(map.id)}`, '_blank')
}

function openDetail(map: MapAdmin): void {
  void router.push({ path: '/platform/plugins/voxelith-map/map-detail', query: { id: map.id } })
}

function openMarkers(map: MapAdmin): void {
  void router.push({ path: '/platform/plugins/voxelith-map/markers', query: { id: map.id } })
}

function confirmArchive(map: MapAdmin): void {
  modal.confirm({
    title: '归档地图',
    content: `确认归档「${map.name}」吗？归档后会从管理列表与公开查看器隐藏（数据保留），可在「归档」页恢复或彻底删除。`,
    onConfirm: () => {
      void api.archiveMap(map.id).then(async () => {
        toast.success(`「${map.name}」已归档`)
        await model.loadMaps(true)
      }).catch((e: unknown) => toast.error(formatError(e, '归档失败')))
    },
  })
}

function mapStateTag(state: MapAdminState) {
  return MAP_STATE_META[state]
}

function isReady(map: MapAdmin): boolean {
  return map.state === 'READY'
}

function isBusy(map: MapAdmin): boolean {
  return map.state === 'RENDERING' || map.state === 'PREPARING' || map.state === 'QUEUED'
}

// ---------------------------------------------------------------------------
// 筛选 + 分页：地图全量列表本就一次拉回（本地服务、规模有限），在客户端做过滤与切片；
// 筛选条件变化时回到第 1 页，删除后若当前页变空则自动回退
// ---------------------------------------------------------------------------

const search = ref('')
const stateFilter = ref('all')
const dimensionFilter = ref('all')
const page = ref(1)
const pageSize = ref(10)

const stateFilterOptions = [
  { label: '状态：全部', value: 'all' },
  ...Object.entries(MAP_STATE_META).map(([value, meta]) => ({ label: meta.label, value })),
]

const dimensionFilterOptions = [
  { label: '维度：全部', value: 'all' },
  ...DIMENSION_OPTIONS,
]

const filtersActive = computed(() =>
  search.value.trim() !== '' || stateFilter.value !== 'all' || dimensionFilter.value !== 'all')

/** 单个维度是否命中筛选（关键词看地图名与存档名，状态与维度看该维度自身）。 */
function matchesFilter(map: MapAdmin): boolean {
  const kw = search.value.trim().toLowerCase()
  if (kw && !map.name.toLowerCase().includes(kw) && !map.worldName.toLowerCase().includes(kw)) return false
  if (stateFilter.value !== 'all' && map.state !== stateFilter.value) return false
  if (dimensionFilter.value !== 'all' && map.dimension !== dimensionFilter.value) return false
  return true
}

/**
 * 命中的存档分组：**按存档收纳**——一个存档一行，维度收在行内切换。
 * 组内只保留命中的维度，一个维度都没命中就整组不显示（筛选维度时正好只留下有这个维度的存档）。
 */
const filteredGroups = computed(() => groupMapsByArchive(model.maps.value)
  .map(group => ({ ...group, dimensions: group.dimensions.filter(matchesFilter) }))
  .filter(group => group.dimensions.length > 0))

const pagedGroups = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredGroups.value.slice(start, start + pageSize.value)
})

/** 行内当前维度：按分组键记住选择，默认组内第一张（主世界）。 */
const dimensionPick = reactive<Record<string, string>>({})

function currentDimensionOf(group: { key: string, dimensions: MapAdmin[] }): MapAdmin {
  const picked = dimensionPick[group.key]
  return group.dimensions.find(item => item.id === picked) ?? group.dimensions[0]!
}

function switchDimension(groupKey: string, mapId: string): void {
  dimensionPick[groupKey] = mapId
}

/** 切换维度的下拉项（行内）。 */
function dimensionPickOptions(group: { dimensions: MapAdmin[] }) {
  return group.dimensions.map(item => ({ label: dimensionLabel(item.dimension), value: item.id }))
}

/**
 * 表格行：**当前维度的地图**本身，外加同组维度。
 *
 * <p>行数据仍是 {@code MapAdmin}，所以既有列模板（状态/规模/操作…）一行都不用改；新增的
 * {@code archiveDimensions} 只给「维度」列的切换器用。</p>
 */
/**
 * 行上额外挂的字段（分组键 / 存档名 / 同组维度）。
 *
 * <p>模板里的 {@code #card} 槽拿到的是行对象本身（类型仍是 MapAdmin），这些附加字段读不到，
 * 所以给一个显式读取口，避免在模板里写 as any。</p>
 */
type ArchiveRow = MapAdmin & {
  archiveKey: string
  archiveName: string
  archiveDimensions: MapAdmin[]
}

const rows = computed<ArchiveRow[]>(() => pagedGroups.value.map((group) => {
  const current = currentDimensionOf(group)
  return {
    ...current,
    archiveKey: group.key,
    archiveName: group.archiveName,
    archiveDimensions: group.dimensions,
  }
}))

const emptyText = computed(() => {
  if (model.loading.value) return '加载中…'
  if (!model.maps.value.length) return '暂无地图，点击右上角「新建地图」开始'
  if (!filteredGroups.value.length) return '没有符合筛选条件的存档'
  return '本页没有地图'
})

// 筛选变化回到第 1 页；数据变少（如删除）后夹紧页码，避免停在空页
watch([search, stateFilter, dimensionFilter], () => {
  page.value = 1
})
watch(() => filteredGroups.value.length, (len) => {
  const maxPage = Math.max(1, Math.ceil(len / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
})

function resetFilters(): void {
  search.value = ''
  stateFilter.value = 'all'
  dimensionFilter.value = 'all'
}

/** 该地图进行中的渲染任务（状态列展示实时进度） */
function activeTaskOf(map: MapAdmin) {
  return model.activeTasksByMapId.value.get(map.id) ?? null
}

/** 地图行操作统一收进操作菜单，归档保持确认流程 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '查看', icon: 'morph:eye', disabled: row => !isReady(row as never), onClick: row => openViewer(row as never) },
  { label: '渲染', icon: 'morph:brush', onClick: row => openDetail(row as never) },
  { label: '标注', icon: 'morph:map-pin', disabled: row => !isReady(row as never), onClick: row => openMarkers(row as never) },
  { label: '归档', icon: 'morph:archive', danger: true, disabled: row => isBusy(row as never), onClick: row => confirmArchive(row as never) },
]

</script>

<template>

  <YdTablePageLayout title="Voxelith 地图管理">
      <template #search>
        <div class="yd-filter-grid" style="--yd-filter-columns: 3">
            <div class="min-w-0">
              <YdInput v-model="search" placeholder="搜索地图名称 / 存档名" clearable class="w-full" />
            </div>
            <div class="min-w-0">
              <YdSelect v-model="stateFilter" :options="stateFilterOptions" class="w-full" />
            </div>
            <div class="min-w-0">
              <YdSelect v-model="dimensionFilter" :options="dimensionFilterOptions" class="w-full" />
            </div>
            <div class="flex items-center gap-2 md:justify-end">
              <YdButton size="sm" variant="outline" :disabled="!filtersActive" @click="resetFilters">
                <YdIcon name="i-mdi:filter-off-outline" />
                重置筛选
              </YdButton>
            </div>
          </div>
      </template>
    <template #actions>
      <div class="flex gap-2">
      <YdButton size="sm" variant="outline" @click="openJarLibrary">
        <YdIcon name="i-mdi:upload" />
        客户端 jar
      </YdButton>
      <YdButton size="sm" variant="outline" @click="router.push('/platform/plugins/voxelith-map/archived')">
        <YdIcon name="i-mdi:archive-outline" />
        归档
      </YdButton>
      <YdButton
        v-if="panelAvailable"
        size="sm"
        variant="outline"
        :disabled="importing"
        @click="openImportDialog"
      >
        <YdIcon name="i-mdi:download" />
        从实例导入
      </YdButton>
      <YdButton size="sm" @click="model.openCreate">
        <YdIcon name="i-mdi:plus" />
        新建地图
      </YdButton>
    </div>
    </template>
    <template #table>
<YdResponsiveEnhancedTable
      :actions="rowActions"

      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[960px]"
      border
      stripe
      :columns="columns"
      :data="rows"
    >
      <template #cell-name="{ row }">
        <YdButton variant="link" class="h-auto px-0" @click="openDetail(row.original)">
          {{ row.original.name }}
        </YdButton>
      </template>
      <template #cell-worldName="{ row }">
        {{ row.original.worldName || '—' }}
      </template>
      <template #cell-dimension="{ row }">
        <!-- 多维度存档：行内切换维度，其余列（状态/规模/操作）跟着切到该维度 -->
        <YdSelect
          v-if="row.original.archiveDimensions.length > 1"
          :model-value="row.original.id"
          :options="dimensionPickOptions({ dimensions: row.original.archiveDimensions })"
          class="min-w-[132px]"
          @update:model-value="value => switchDimension(row.original.archiveKey, String(value))"
        />
        <span v-else>{{ dimensionLabel(row.original.dimension) }}</span>
      </template>
      <template #cell-state="{ row }">
        <div class="grid min-w-[160px] gap-1">
          <YdTag :variant="mapStateTag(row.original.state).variant">
            {{ mapStateTag(row.original.state).label }}
          </YdTag>
          <template v-if="activeTaskOf(row.original)">
            <YdProgress :model-value="renderTaskProgress(activeTaskOf(row.original)!)" />
            <span class="text-xs text-muted-foreground">
              {{ renderTaskProgress(activeTaskOf(row.original)!) }}%
            </span>
          </template>
          <span v-else-if="row.original.state === 'FAILED' && row.original.lastError" class="min-w-0 truncate text-xs text-destructive" :title="row.original.lastError">
            {{ row.original.lastError }}
          </span>
        </div>
      </template>
      <template #cell-scale="{ row }">
        <span class="text-sm text-muted-foreground">{{ row.original.scaleDescription || '—' }}</span>
      </template>
      <template #cell-updatedAt="{ row }">
        {{ formatTime(row.original.updatedAt) }}
      </template>
      <template #empty>
        <span class="text-muted-foreground">{{ emptyText }}</span>
      </template>
</YdResponsiveEnhancedTable>
    </template>
    <template #footer>
      <YdPagination
      v-model:page="page"
      v-model:size="pageSize"
      :total="filteredGroups.length"
    />
    </template>
  </YdTablePageLayout>




    <YdModal v-model="model.createOpen.value" title="新建地图">
      <div class="grid gap-4">
        <!-- 地图名与存档名合并为一个：列表、查看器、同步来源与维度都挂在这一份「存档」上 -->
        <label class="grid gap-1.5">
          <span class="text-sm">存档名称</span>
          <YdInput v-model="model.createForm.name" placeholder="例如：生存世界" />
          <span class="text-xs text-muted-foreground">
            地图名与存档名合并为一个：管理列表、查看器与「同步最新存档」都用它
          </span>
        </label>

        <label class="grid gap-1.5">
          <span class="text-sm">存档维度</span>
          <YdSwitch v-model="model.createForm.allDimensions" />
          <span class="text-xs text-muted-foreground">
            {{
              model.createForm.allDimensions
                ? '开启：按存档包内的目录一次建出全部维度（world＝主世界、world_nether＝下界、world_the_end＝末地），共享同一份存档'
                : '关闭：只建下面指定的那一个维度'
            }}
          </span>
        </label>

        <label v-if="!model.createForm.allDimensions" class="grid gap-1.5">
          <span class="text-sm">指定维度</span>
          <YdSelect v-model="model.createForm.dimension" :options="DIMENSION_OPTIONS" />
          <span class="text-xs text-muted-foreground">
            维度跟随这份存档：同一存档的主世界/下界/末地需要分别创建地图
          </span>
        </label>

        <label class="grid gap-1.5">
          <span class="text-sm">最低渲染高度 (minY)</span>
          <YdNumberField v-model="model.createForm.minY" :min="-2048" :max="2048" :step="1" />
        </label>

        <div class="grid gap-1.5">
          <span class="text-sm">存档 zip（必填）</span>
          <YdFileUpload
            v-model="worldFiles"
            :max="1"
            :disabled="model.creating.value"
            :before-upload="isZipFile"
            :http-request="selectWorldFile"
            description="拖放或点击选择世界存档 ZIP 压缩包"
          />
          <span v-if="detectedWorldDimension" class="text-xs text-muted-foreground">
            已按包名判定维度：{{ dimensionLabel(detectedWorldDimension) }}（可手动改）
          </span>
          <span class="text-xs text-muted-foreground">
            存档各维度各自对应一张地图：world＝主世界、world_nether＝下界、world_the_end＝末地；
            要三个维度就分别上传三次、各建一张地图
          </span>
        </div>

        <div class="grid gap-1.5">
          <span class="text-sm">原版客户端 jar</span>
          <YdSelect v-model="clientJarChoice" :options="jarLibraryOptions" />
          <span v-if="clientJarChoice === 'auto'" class="text-xs text-muted-foreground">
            不上传时按存档版本（level.dat）自动从官方源/镜像下载原版 client jar
          </span>
          <YdFileUpload
            v-if="clientJarChoice === 'upload'"
            v-model="clientJarFiles"
            :max="1"
            :disabled="model.creating.value"
            :before-upload="isJarFile"
            :http-request="selectClientJar"
            description="拖放或点击选择客户端 JAR；文件名请保留版本号（如 client-1.21.1.jar），引擎按它自动判定采集版本"
          />
        </div>

        <div class="grid gap-1.5">
          <span class="text-sm">模组（可选）</span>
          <YdFileUpload
            v-model="modJarItems"
            multiple
            :disabled="model.creating.value"
            :before-upload="isModFile"
            :http-request="selectModJar"
            description="可一次多选模组 JAR，或选整个 mods 目录的 zip 包；其方块与贴图会随渲染一并出现，zip 由后端解包，JAR 按选择顺序叠加，后选的覆盖先选的"
          />
          <div v-if="packInspect.error.value" class="text-xs text-destructive">{{ packInspect.error.value }}</div>
          <div v-if="packInspect.loading.value" class="grid gap-1">
            <div class="flex items-center gap-2 text-xs">
              <YdIcon name="i-mdi:progress-upload" />
              <span>
                {{ packInspect.stage.value === 'uploading' ? '正在上传模组包…' : '正在解析包内 jar…' }}
                已等待 {{ packInspect.elapsedSeconds.value }} 秒
              </span>
            </div>
            <span class="text-xs text-muted-foreground">
              解析要读一遍整个包，大包会慢；完成后即可看到包内 jar 列表
            </span>
          </div>
          <div v-if="packInspect.listing.value" class="rounded-md border border-border p-2">
            <div class="mb-1 flex flex-wrap items-center gap-2 text-xs">
              <span class="font-medium">包内模组 jar：{{ packInspect.listing.value.jarCount }} 个</span>
              <span v-if="packInspect.finishedAt.value" class="text-muted-foreground">
                （解析于 {{ packInspect.finishedAt.value }}）
              </span>
              <span v-if="packInspect.listing.value.truncated" class="text-muted-foreground">
                （仅列出前 {{ packInspect.listing.value.jars.length }} 个）
              </span>
            </div>
            <div
              v-if="packInspect.listing.value.jarCount"
              class="grid max-h-32 gap-1 overflow-y-auto pr-1 font-mono text-xs"
            >
              <span v-for="jar in packInspect.listing.value.jars" :key="jar.name" class="truncate" :title="jar.name">
                {{ jar.name }}
              </span>
            </div>
            <div v-else class="text-xs text-muted-foreground">
              包内没有 jar 文件：请确认选的是 mods 目录的 zip 包
            </div>
          </div>
        </div>

        <p v-if="model.createStep.value" class="text-sm text-muted-foreground">
          {{ model.createStep.value }}
        </p>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" :disabled="model.creating.value" @click="model.createOpen.value = false">
            取消
          </YdButton>
          <YdButton
            :disabled="!model.createValid.value"
            :loading="model.creating.value"
            @click="model.submitCreate"
          >
            创建
          </YdButton>
        </div>
      </template>
    </YdModal>

  <!-- 客户端 jar 库管理 -->
  <YdModal v-model="jarLibraryOpen" title="客户端 jar 库">
    <div class="grid gap-4">
      <div class="grid gap-2 rounded-md border border-dashed border-border p-3">
        <span class="text-sm">上传新版本</span>
        <YdFileUpload
          v-model="newJarFiles"
          :max="1"
          :disabled="newJarUploading"
          :before-upload="isJarUpload"
          :http-request="selectNewJar"
          description="jar 文件；文件名含版本号（如 client-1.21.8.jar）时自动回填版本号"
        />
        <div class="flex flex-wrap items-center gap-2">
          <YdInput v-model="newJarVersion" placeholder="版本号，例如 1.21.8（选择文件后自动识别）" class="min-w-0 flex-1" />
          <YdButton shrink-0 :loading="newJarUploading" @click="uploadJarToLibrary">
            <YdIcon name="i-mdi:upload" />
            上传到库
          </YdButton>
        </div>
      </div>

      <div class="grid gap-1.5">
        <span class="text-sm">已上传版本（{{ jarList.length }}）</span>
        <div
          v-for="entry in jarList"
          :key="entry.version"
          class="flex items-center gap-2 rounded-md border border-border px-2 py-1.5 text-sm"
        >
          <span class="min-w-0 flex-1 truncate">
            <b>{{ entry.version }}</b>
            <span class="ml-2 text-xs text-muted-foreground">{{ entry.fileName }} · {{ mb(entry.sizeBytes) }} · {{ formatTime(entry.createdAt) }}</span>
          </span>
          <YdButton size="sm" variant="ghost" title="删除" @click="removeJarFromLibrary(entry)">
            <YdIcon name="i-mdi:delete-outline" />
          </YdButton>
        </div>
        <div
          v-if="!jarLoading && !jarList.length"
          class="rounded-md border border-dashed border-border p-3 text-center text-xs text-muted-foreground"
        >
          还没有预上传的客户端 jar；不上传时渲染会按存档版本自动下载
        </div>
        <div v-if="jarLoading" class="text-xs text-muted-foreground">加载中…</div>
      </div>
    </div>
    <template #footer>
      <div class="flex justify-end">
        <YdButton variant="outline" @click="jarLibraryOpen = false">
          完成
        </YdButton>
      </div>
    </template>
  </YdModal>

  <!-- 从 MC 面板实例快捷导入（独立窗口） -->
  <YdModal v-model="importOpen" title="从 MC 面板实例导入">
    <div class="grid gap-4">
      <label class="grid gap-1.5">
        <span class="text-sm">地图名称（留空则用实例名）</span>
        <YdInput v-model="importName" placeholder="例如：生存世界" />
      </label>
      <label class="grid gap-1.5">
        <span class="text-sm">面板实例</span>
        <YdSelect
          v-model="panelInstanceChoice"
          :options="panelInstances.map(i => ({ label: `${i.name}（${i.mcVersion || '未知版本'}${i.state ? ' · ' + i.state : ''}）`, value: i.id }))"
          placeholder="选择实例"
        />
      </label>
      <label class="flex items-center gap-2 text-sm">
        <YdSwitch v-model="importMods" />
        同步导入实例模组（mods 目录下的全部 jar）
      </label>
      <span v-if="detectedVersion" class="text-xs text-muted-foreground">
        存档版本：{{ detectedVersion }}（来自 {{ detectedVersionFrom }}）
      </span>
      <span v-else class="text-xs text-muted-foreground">
        未在实例根目录识别到带版本号的 jar，导入时将按自动判定处理
      </span>
      <span v-if="panelInstanceChoice && detectedWorldDirs.length" class="text-xs text-muted-foreground">
        将导入 {{ detectedWorldDirs.length }} 个维度目录：{{ detectedWorldDirs.join('、') }}
        （每个维度各建一张地图；world＝主世界、world_nether＝下界、world_the_end＝末地）
      </span>
      <span v-else-if="panelInstanceChoice" class="text-xs text-muted-foreground">
        未能读取实例根目录，将按主世界（world）目录导入
      </span>
      <span class="text-xs text-muted-foreground">
        自动打包实例存档目录为存档、按存档版本下载 client jar 并创建地图；建议实例已停止。
      </span>
      <!-- 搬运方式随面板插件能力自动切换：有实例文件契约走服务端作业，否则由本页搬运（同样登记任务） -->
      <span v-if="serverImport" class="text-xs text-muted-foreground">
        提交后由服务端搬运：本窗口即关闭，导入不会因刷新或关闭页面而中断，进度与日志见任务中心。
      </span>
      <span v-else class="text-xs text-muted-foreground">
        当前面板插件未提供服务端导入契约，搬运由本页完成（面板插件无需升级）。导入会在任务中心登记为
        一条任务，进度与日志可查；但搬运期间请不要刷新或关闭本页面，否则会中断（进度也显示在本页右下角）。
      </span>
      <YdButton
        v-if="serverImport"
        variant="link"
        class="h-auto justify-start px-0 text-xs"
        @click="openTaskCenter"
      >
        前往任务中心查看进度与日志
      </YdButton>
      </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <YdButton variant="outline" @click="importOpen = false">
          取消
        </YdButton>
        <YdButton
          :disabled="!panelInstanceChoice || importing"
          :loading="importing"
          @click="confirmImport"
        >
          导入并创建
        </YdButton>
      </div>
    </template>
  </YdModal>

  <!-- 浏览器搬运的进度浮层（应用级状态驱动：页面切换回来仍在）；任务详情在任务中心 -->
  <div
    v-if="browserImport.active"
    class="fixed bottom-4 right-4 z-50 w-80 max-w-[calc(100vw-2rem)]"
  >
    <YdCard class="gap-3 py-4 shadow-lg">
      <div class="grid gap-2">
        <div class="flex items-center justify-between gap-2 text-xs">
          <span class="font-medium text-foreground">实例导入进行中</span>
          <span v-if="browserImport.percent >= 0" class="text-muted-foreground">{{ browserImport.percent }}%</span>
        </div>
        <!-- 比例可量时给确定进度；节点打包这类量不出比例的阶段用扫掠动画表达进行中 -->
        <YdProgress
          v-if="browserImport.percent >= 0"
          :model-value="browserImport.percent"
        />
        <div v-else class="voxelith-progress-indeterminate"><span /></div>
        <span class="text-xs text-muted-foreground">
          {{ browserImport.stage }}{{ browserImport.detail ? ` · ${browserImport.detail}` : '' }}
        </span>
        <span class="text-xs text-muted-foreground">
          搬运由浏览器完成：导入期间请不要刷新或关闭本页面，否则会中断（渲染与删除不受影响）。
        </span>
        <div class="flex items-center justify-between gap-2">
          <YdButton size="sm" variant="link" class="h-auto px-0 text-xs" @click="openTaskCenter">
            查看任务
          </YdButton>
          <YdButton size="sm" variant="destructive" @click="cancelBrowserImport">
            取消导入
          </YdButton>
        </div>
      </div>
    </YdCard>
  </div>
</template>
