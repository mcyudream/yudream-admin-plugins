<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  YdButton,
  YdCard,
  YdDrawer,
  YdFileUpload,
  YdIcon,
  YdInput,
  YdNumberField,
  YdPageHeader,
  YdPageMain,
  YdProgress,
  YdResponsiveTable,
  YdSelect,
  YdSwitch,
  YdTag,
  useYdModal,
  useYdToast,
} from '@yudream/components'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  dimensionLabel,
  formatError,
  formatTime,
  MAP_STATE_META,
  MC_VERSION_HINT,
  PANORAMA_STATE_META,
  renderPhaseLabel,
  SHARD_WORKER_STATE_META,
  TASK_STATE_META,
} from '../../composables/useVoxelithAdmin'
import { useTaskStream } from '../../composables/useTaskStream'
import { createVoxelithMapAdminApi, fetchImageObjectUrl } from '../../api/voxelith-map-admin-api'
import { packWorldZip } from '../../composables/mcpanel-world'
import { downloadTaskLog } from '../../api/voxelith-map-admin-api'
import { clockTime, describeFormChanges } from '../../composables/formDiff'
import { buildDimensionChoices } from '../../composables/dimensionSwitch'
import type { DimensionChoice } from '../../composables/dimensionSwitch'
import { useModPackInspect } from '../../composables/useModPackInspect'
import { createVoxelithMapApi } from '../../api/voxelith-map-api'
// 三维栈按需加载：静态 import 会把 three.js 与 vendored 渲染核并进 remoteEntry.js，
// 让本页乃至全部管理页刷新时白白解析求值整份 3D 栈（见 src/viewer/stack.ts）
import { loadViewerStack } from '../../viewer/stack'
import type { VoxelithViewer } from '../../viewer/VoxelithViewer'
import CollapsibleSection from '../../components/CollapsibleSection.vue'
import type {
  MapAdmin,
  MapRenderSettings,
  ModJar,
  PanoramaStatus,
  Preview,
  RenderTask,
  RenderTaskState,
  ShardWorkerStatus,
  UpdateMapPayload,
} from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const toast = useYdToast()
const api = createVoxelithMapAdminApi(props.sdk)
const publicApi = createVoxelithMapApi(props.sdk)

const routeMapId = computed(() => String(props.route?.query?.id ?? ''))
/**
 * 页面当前展示的地图 id。
 *
 * <p>**不能再直接用 route.query.id**：切换维度只改 query（同一个路由记录），宿主若没有把
 * 新的 route 传下来，页面就永远不会重载——表现就是「点了维度按钮没反应」。所以页面自己持有
 * 当前 id，切换时立即改它（页面随即重载），同时把 URL 同步过去（可分享、刷新后仍在同一维度）；
 * 宿主侧的外部变更（浏览器前进/后退、从列表跳进来）再由下面这个 watcher 跟随。</p>
 */
const currentMapId = ref(routeMapId.value)
watch(routeMapId, (id) => {
  if (id && id !== currentMapId.value) currentMapId.value = id
})
const map = ref<MapAdmin | null>(null)
const loading = ref(false)
const error = ref('')

/**
 * 同一世界的其它维度（含当前这张与尚未渲染的）。
 *
 * <p>各维度是各自独立的地图（各自渲染、各自瓦片），所以「切换维度」＝切到兄弟地图；
 * 只有一个维度时不出切换器。</p>
 */
const dimensionChoices = ref<DimensionChoice[]>([])
/** 维度列表读取失败的原因（静默失败会让「切换没反应」无法排查，所以显示出来） */
const dimensionError = ref('')

// 嵌入的只读 3D viewer。必须 shallowRef：深层 reactive 代理会包裹 THREE 相机/场景，
// 渲染时访问不可配置属性（modelViewMatrix 等）触发 Proxy 不变量报错
const viewerContainer = ref<HTMLCanvasElement | null>(null)
const viewer = shallowRef<VoxelithViewer | null>(null)
const viewerError = ref('')
/**
 * 三维预览是否已由用户主动加载。
 *
 * <p>详情页默认不自动起三维渲染：它会在页面活着的整段时间里持续跑渲染循环并按需拉整图瓦片
 * （几百到几千个请求），管理页的首要任务是看数据、改参数，不该一进来就占满 CPU 与同源连接。
 * 用户点一下「加载三维预览」再加载；需要完整浏览时用「打开查看器」。</p>
 */
const preview3dEnabled = ref(false)

// 渲染参数表单
const updateForm = reactive<{
  name: string
  worldName: string
  dimension: string
  settings: MapRenderSettings
  regionMinX: number
  regionMinZ: number
  regionMaxX: number
  regionMaxZ: number
  clearRegion: boolean
}>({
  name: '',
  worldName: 'world',
  dimension: 'minecraft:overworld',
  settings: {
    minY: -64,
    maxLevel: 0,
    lodAtlas: true,
    meshopt: true,
    maxChunks: 0,
    batchChunks: 2048,
    skipHarvest: false,
    mcVersion: '',
    loaderVersion: '0.16.14',
  },
  regionMinX: 0,
  regionMinZ: 0,
  regionMaxX: 0,
  regionMaxZ: 0,
  clearRegion: false,
})
const savingParams = ref(false)

// ---------------------------------------------------------------------------
// 模组 jar 管理（整体替换语义：上传任意文件保存 = 替换整个列表；勾选清空 = 清成纯原版）
// ---------------------------------------------------------------------------

const modJarItems = ref<FileItem[]>([])
const pendingModFiles = ref<File[]>([])
const clearModJars = ref(false)
const modJarSaving = ref(false)

function isJarFile(file: File): boolean {
  return file.type === 'application/java-archive' || file.name.toLowerCase().endsWith('.jar')
}

/** 模组文件：单个 jar，或整个 mods 目录的 zip 包（后端解包逐个落库） */
function isModFile(file: File): boolean {
  return isZipFile(file) || isJarFile(file)
}

function isZipFile(file: File): boolean {
  return file.type === 'application/zip' || file.name.toLowerCase().endsWith('.zip')
}

function selectModJar(options: FileUploadRequestOptions) {
  pendingModFiles.value = [...pendingModFiles.value, options.file]
  // zip 包选中即上传并解析：立刻能看到包内有哪些模组 jar，保存时复用这次上传（不重复传一遍）
  if (isZipFile(options.file)) {
    void packInspect.uploadAndInspect(options.file)
  }
  options.onProgress(100)
  return { selected: true }
}

// 组件内移除某个文件时，把待上传模组按名字对齐删掉
watch(modJarItems, (items) => {
  if (items.length === pendingModFiles.value.length) return
  const names = new Set(items.map(item => item.name))
  pendingModFiles.value = pendingModFiles.value.filter(file => names.has(file.name))
  if (pendingModFiles.value.length > 0) clearModJars.value = false
})

watch(clearModJars, (clear) => {
  if (clear) {
    modJarItems.value = []
    pendingModFiles.value = []
  }
})

/** 上传结果：单个 jar 进 modJars；mods 目录整包 zip 登记成一个模组包引用。 */
interface ModUploadResult {
  modJars: ModJar[]
  modsPackFileId: string
  modsPackName: string
}

/** 模组区块副标题：区分「模组包 + 若干 jar」与「纯原版」。 */
const modsSubtitle = computed(() => {
  const jars = map.value?.modJars?.length ?? 0
  const pack = map.value?.modsPackFileId ? 1 : 0
  if (!jars && !pack) return '当前为纯原版'
  return pack
    ? `模组包 ${map.value?.modsPackName || 'mods.zip'}${jars ? ` + ${jars} 个 jar` : ''}`
    : `当前绑定 ${jars} 个 jar`
})

async function uploadModJars(): Promise<ModUploadResult> {
  const modJars: ModJar[] = []
  let modsPackFileId = ''
  let modsPackName = ''
  for (const file of pendingModFiles.value) {
    if (isZipFile(file)) {
      // 整包 zip 只记引用：导入时逐个解包成 jar 会打出成百上千次写请求、渲染时再逐个读回来。
      // 选中时已经上传过（为了立即解析），这里直接复用那份，避免 GB 级包传两遍
      if (packInspect.matchesPending(file)) {
        modsPackFileId = packInspect.pendingFileId.value
        modsPackName = file.name
        continue
      }
      const uploadedZip = await props.sdk.files.uploadImage(file, { module: 'voxelith-map', publicAccess: false })
      if (!uploadedZip.id) {
        throw new Error(`模组包 ${file.name} 上传后未返回 id`)
      }
      modsPackFileId = uploadedZip.id
      modsPackName = file.name
      continue
    }
    // 一律经宿主文件上传通道（二进制 multipart，GB 级也不拼 base64 字符串）
    const uploaded = await props.sdk.files.uploadImage(file, { module: 'voxelith-map', publicAccess: false })
    if (!uploaded.id) {
      throw new Error(`模组 ${file.name} 上传后未返回 id`)
    }
    modJars.push({ fileId: uploaded.id, name: file.name })
  }
  return { modJars, modsPackFileId, modsPackName }
}

// ---------------------------------------------------------------------------
// 模组包：选中 zip 立即上传并解析；已绑定的包可随时解析；也可以只删除包引用
// ---------------------------------------------------------------------------

/** 待保存的包：选中即上传 + 解析（见 composables/useModPackInspect，保存时复用其 fileId） */
const packInspect = useModPackInspect(props.sdk)

/** 字节数展示（与地图列表页同口径）。 */
function mb(sizeBytes: number): string {
  return sizeBytes > 0 ? `${(sizeBytes / 1024 / 1024).toFixed(1)}MiB` : '—'
}

/** 解析已绑定在地图上的模组包（手动「解析包内 jar」按钮）。 */
function inspectBoundModPack(): void {
  const pack = map.value?.modsPackFileId
  if (!pack) return
  void packInspect.inspect(pack, map.value?.modsPackName || '')
}

/**
 * 已绑定的模组包在进页面、保存后自动解析一次。
 *
 * <p>包内清单只从「解析」结果来，而解析态是组件内的：以前保存时会连清单一起清掉、刷新页面更是
 * 只剩一个包名，必须再手点一次「解析包内 jar」才看得到内容。绑定关系本来就在地图上，进页面直接
 * 还原清单才是符合预期的（大包解析过程有进度可见，不会静默）。</p>
 */
function inspectBoundModPackIfNeeded(): void {
  const pack = map.value?.modsPackFileId
  if (!pack || packInspect.loading.value || packInspect.listing.value) return
  void packInspect.inspect(pack, map.value?.modsPackName || '')
}

/** 删除模组包引用（只清「包」这一个引用，单独上传的 jar 不受影响）。 */
function removeModPack(): void {
  if (!map.value?.modsPackFileId) return
  modal.confirm({
    title: '删除模组包',
    content: `确认从「${map.value.name}」移除模组包「${map.value.modsPackName || 'mods.zip'}」吗？`
      + '该包内的模组将不再参与渲染（单独上传的 jar 不受影响）；重新渲染后生效。',
    onConfirm: () => {
      void api.updateMap(map.value!.id, { modsPackFileId: '' }).then(async () => {
        packInspect.clear()
        toast.success('已删除模组包')
        await loadMap()
      }).catch((e: unknown) => toast.error(formatError(e, '删除模组包失败')))
    },
  })
}

// 预览底图 + 框选
const preview = ref<Preview | null>(null)
const previewLoading = ref(false)
const previewImgError = ref(false)
const previewObjectUrl = ref<string | null>(null)
/** 取图请求进行中：<img> 的 onerror 兜底不该和主动取图重复发请求。 */
let previewImgPending = false
let previewTimer: ReturnType<typeof setTimeout> | null = null

const selection = reactive({ active: false, x0: 0, y0: 0, x1: 0, y1: 0 })
const dragging = ref(false)
const previewImgEl = ref<HTMLImageElement | null>(null)

// 任务流：SSE + 2 秒轮询兜底
const mapIdRef = computed(() => (currentMapId.value ? currentMapId.value : undefined))
const taskStream = useTaskStream(api, { mapId: mapIdRef, scoped: true })

const errorDrawerOpen = ref(false)
const selectedTaskError = ref<RenderTask | null>(null)

const DIMENSION_OPTIONS = [
  { label: '主世界', value: 'minecraft:overworld' },
  { label: '下界', value: 'minecraft:nether' },
  { label: '末地', value: 'minecraft:the_end' },
]

// 不给时间等短内容列设最小宽度：表格随容器自适应，窄容器不再触发整行左右滑动；
// 长 ID 与长报错一律单元格内截断，悬停/抽屉看全量
const taskColumns: TableColumn<RenderTask>[] = [
  { id: 'id', header: '任务', width: 110 },
  { id: 'state', header: '状态', width: 90 },
  { id: 'phase', header: '阶段', width: 120 },
  { id: 'progress', header: '进度 / 错误', width: 240 },
  { id: 'createdAt', header: '创建时间' },
  { id: 'finishedAt', header: '完成时间' },
]

const mapStateTag = (state: MapAdmin['state']) => MAP_STATE_META[state]
const isBusy = (state: MapAdmin['state']) =>
  state === 'RENDERING' || state === 'PREPARING' || state === 'QUEUED'
const isReady = (state: MapAdmin['state']) => state === 'READY'

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

/**
 * 载入同一世界的各维度地图。
 *
 * <p>渲染页要的是**全部**维度（含还没渲染的 EMPTY）：这一个世界通常要渲染三次，
 * 有这份清单才能一眼看出哪个维度还没渲染、直接切过去。失败只当没有切换器，不影响本页。</p>
 */
async function loadDimensions(): Promise<void> {
  const id = currentMapId.value
  if (!id) return
  dimensionError.value = ''
  try {
    const res = await api.dimensions(id)
    // 期间可能已经切到别的地图：结果只属于发起时的那张，过期的丢掉
    if (currentMapId.value !== id) return
    dimensionChoices.value = buildDimensionChoices(res.maps ?? [], id)
  }
  catch (e) {
    if (currentMapId.value !== id) return
    dimensionChoices.value = []
    // 静默失败会让「维度切换没反应」无法与「本来就没有同伴维度」区分，必须把原因显示出来
    dimensionError.value = formatError(e, '维度列表加载失败')
  }
}

/**
 * 切换维度：**先改页面自持的当前地图 id**（页面立刻重载，不依赖宿主是否重传 route），
 * 再把 URL 同步过去（可分享、刷新后仍停在这个维度）。
 */
function switchDimension(id: string): void {
  if (!id || id === currentMapId.value) return
  currentMapId.value = id
  void router.replace({ path: '/platform/plugins/voxelith-map/map-detail', query: { id } })
    .catch(() => {})
}

/**
 * 维度按钮上的状态提示：已就绪的不标，其余按状态文案（未渲染 / 渲染中 / 失败…）。
 *
 * <p>渲染页的价值就在这一行——一眼看出哪个维度还没渲染，切过去按渲染即可。</p>
 */
function dimensionStateLabel(choice: DimensionChoice): string {
  if (choice.ready) return ''
  if (!choice.state) return '未渲染'
  return MAP_STATE_META[choice.state as MapAdmin['state']]?.label ?? choice.state
}

/** 维度行上的操作进行中（更新/下载共用一个防重入标志：两个动作都不该叠加发起）。 */
const dimensionActionId = ref('')

/**
 * 更新某个维度：提交该维度地图的渲染任务。
 *
 * <p>不切页：一次导入建出的三个维度里，通常是「先把最想看的渲染上，顺手把另两个也排队」，
 * 留在当前页看自己的进度更合适；别的维度的进度与日志在任务中心，任务流也会把状态带回来。</p>
 */
async function renderDimension(choice: DimensionChoice): Promise<void> {
  if (dimensionActionId.value || isBusy(choice.state as MapAdmin['state'])) return
  dimensionActionId.value = choice.id
  try {
    await api.render(choice.id)
    toast.success(`已提交「${choice.label}」的渲染任务，进度与日志见任务中心`)
    await loadDimensions()
  }
  catch (e) {
    toast.error(formatError(e, `触发「${choice.label}」渲染失败`))
  }
  finally {
    dimensionActionId.value = ''
  }
}

/** 下载某个维度的存档压缩包（后端把该图绑定的存档回传为 base64，转 Blob 后另存）。 */
async function downloadDimensionWorld(choice: DimensionChoice): Promise<void> {
  if (dimensionActionId.value) return
  dimensionActionId.value = choice.id
  try {
    const res = await api.downloadWorldZip(choice.id)
    saveBase64Zip(res.zipBase64, `${choice.name || choice.id}-world.zip`)
  }
  catch (e) {
    toast.error(formatError(e, `下载「${choice.label}」的存档失败`))
  }
  finally {
    dimensionActionId.value = ''
  }
}

/** base64 的 zip 转成 Blob 另存（存档下载与维度下载共用）。 */
function saveBase64Zip(zipBase64: string, fileName: string): void {
  const bytes = Uint8Array.from(atob(zipBase64), c => c.charCodeAt(0))
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/zip' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(url)
}

async function loadMap(silent = false): Promise<void> {
  if (!currentMapId.value) {
    error.value = '缺少地图 id'
    return
  }
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const m = await api.map(currentMapId.value)
    map.value = m
    // 回填表单会触发 deep watch：抑制一次，避免加载即触发自动保存
    suppressAutoSave = true
    updateForm.name = m.name
    updateForm.worldName = m.worldName
    savedFormSnapshot = JSON.parse(JSON.stringify(updateForm)) as Record<string, unknown>
    updateForm.dimension = m.dimension
    updateForm.settings = { ...m.settings }
    // 同一世界的其它维度（含未渲染的）：管理员据此逐个切过去渲染
    void loadDimensions()
    if (m.region) {
      updateForm.regionMinX = m.region.minX
      updateForm.regionMinZ = m.region.minZ
      updateForm.regionMaxX = m.region.maxX
      updateForm.regionMaxZ = m.region.maxZ
      updateForm.clearRegion = false
    }
    else {
      updateForm.regionMinX = 0
      updateForm.regionMinZ = 0
      updateForm.regionMaxX = 0
      updateForm.regionMaxZ = 0
      updateForm.clearRegion = true
    }
    // 已绑定的模组包：进页面即还原包内清单（刷新后不再只剩一个包名）
    inspectBoundModPackIfNeeded()
    if (m.state === 'READY' && preview3dEnabled.value) {
      await initViewer()
    }
    else {
      // 收起上一张图的三维渲染：换了地图不再保留画布与渲染循环
      viewer.value?.dispose()
      viewer.value = null
    }
  }
  catch (e) {
    if (!silent) {
      error.value = formatError(e, '地图详情加载失败')
    }
  }
  finally {
    await nextTick()
    suppressAutoSave = false
    if (!silent) loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 切换维度 / 切换地图
// ---------------------------------------------------------------------------

/**
 * 同一路由换 query（切到另一维度的地图）时组件**会被复用**，`onMounted` 不会再跑。
 *
 * <p>所以这里自己把该地图的数据重新拉一遍，并把只属于上一张图的状态清掉：预览底图（object URL
 * 必须释放，否则每切一次泄漏一份）、三维预览、全景与分片状态、维度清单。任务订阅
 * （{@code useTaskStream}）按 mapIdRef 走，会自己跟上，不在这一份里。</p>
 */
watch(currentMapId, (id, previous) => {
  if (!id || id === previous) return
  // 先卸掉旧地图，页面进入「加载中」而不是短暂显示上一张图的数据
  map.value = null
  releasePreviewObjectUrl()
  preview.value = null
  previewImgError.value = false
  preview3dEnabled.value = false
  viewer.value?.dispose()
  viewer.value = null
  panorama.value = null
  panoramaRevision.value = 0
  shard.value = null
  dimensionChoices.value = []
  if (panoramaTimer !== null) {
    clearTimeout(panoramaTimer)
    panoramaTimer = null
  }
  if (shardTimer !== null) {
    clearTimeout(shardTimer)
    shardTimer = null
  }
  syncing.value = false
  // 自动同步偏好按地图记忆：换了地图要读新地图的，而不是沿用上一张的
  try {
    autoSyncEnabled.value = localStorage.getItem(AUTO_SYNC_KEY.value) === '1'
    const saved = localStorage.getItem(AUTO_SYNC_MINUTES_KEY.value)
    autoSyncMinutes.value = saved ? Math.max(1, parseInt(saved, 10) || 10) : 10
  } catch {}
  if (autoSyncEnabled.value) startAutoSync()
  void loadMap()
  void loadPanorama()
  void loadShard()
  void refreshClientJarVersions()
})

// ---------------------------------------------------------------------------
// 嵌入 viewer + 标注
// ---------------------------------------------------------------------------

/** 用户主动加载三维预览：置位后再初始化（画布由 v-if 挂载，initViewer 内部等一帧）。 */
async function loadPreview3d(): Promise<void> {
  preview3dEnabled.value = true
  await initViewer()
}

async function initViewer(): Promise<void> {
  // canvas 由 v-if="isReady(map.state)" 控制挂载：地图刚变 READY 时 DOM 还没 patch，
  // ref 必为 null。必须先等一帧，否则 viewer 根本不建立，画布只剩背景色（黑屏）。
  await nextTick()
  if (!viewerContainer.value || !currentMapId.value) return
  viewerError.value = ''
  viewer.value?.dispose()
  viewer.value = null
  try {
    const baseUrl = publicApi.mapBaseUrl(currentMapId.value)
    const { loadManifest, VoxelithViewer } = await loadViewerStack()
    const manifest = await loadManifest(baseUrl)
    viewer.value = new VoxelithViewer(viewerContainer.value, {
      mapBaseUrl: baseUrl,
      manifest,
      mode: 'flight',
    })
  }
  catch (e) {
    const message = e instanceof Error ? e.message : '预览初始化失败'
    // 404 不是普通的「加载失败」：地图记录上写着已发布，但存储里没有该代的 manifest
    // （产物被清理或丢失）。这种状态重试多少次都一样，必须说清并指向唯一出路——重新渲染。
    viewerError.value = /404/.test(message)
      ? '该地图的渲染产物在存储中已不存在（记录显示已发布，但产物缺失）。重新触发一次渲染即可恢复预览。'
      : message
  }
}

// ---------------------------------------------------------------------------
// 渲染参数 / 范围保存
// ---------------------------------------------------------------------------

/**
 * 保存渲染设置。返回 'saved' | 'failed' | 'skipped'（有保存正在进行时跳过）。
 * 自动保存模式下不再整页 loadMap 重置表单，只把 map 引用换成服务端返回的最新值，
 * 避免用户正在输入的字段被请求往返中途重置。
 */
async function saveParams(): Promise<'saved' | 'failed' | 'skipped'> {
  if (!currentMapId.value) return 'failed'
  if (savingParams.value) return 'skipped'
  savingParams.value = true
  modJarSaving.value = true
  try {
    const payload: UpdateMapPayload = {
      name: updateForm.name.trim(),
      // 存档名跟随名称：字段仍然保留（预览上传等会用到），但不再单独维护
      worldName: updateForm.name.trim() || 'world',
      dimension: updateForm.dimension,
      settings: { ...updateForm.settings },
    }
    // 模组：只有动了才发字段。清空 = 传 [] 并且清掉模组包；替换 = 传新上传的完整列表
    if (clearModJars.value) {
      payload.modJars = []
      payload.modsPackFileId = ''
    }
    else if (pendingModFiles.value.length > 0) {
      const uploaded = await uploadModJars()
      payload.modJars = uploaded.modJars
      if (uploaded.modsPackFileId) {
        payload.modsPackFileId = uploaded.modsPackFileId
        payload.modsPackName = uploaded.modsPackName
      }
    }
    modJarSaving.value = false
    if (updateForm.clearRegion) {
      payload.clearRegion = true
    }
    else {
      payload.regionMinX = updateForm.regionMinX
      payload.regionMinZ = updateForm.regionMinZ
      payload.regionMaxX = updateForm.regionMaxX
      payload.regionMaxZ = updateForm.regionMaxZ
    }
    const updated = await api.updateMap(currentMapId.value, payload)
    if (updated) {
      map.value = updated
    }
    // 提示要能回答「保存了什么、什么时候」：对比上一次已落盘的快照，列出真正变化的字段
    const changed = describeFormChanges(savedFormSnapshot, updateForm)
    savedFormSnapshot = JSON.parse(JSON.stringify(updateForm)) as Record<string, unknown>
    autoSaveDetail.value = changed.length ? changed.join('、') : '渲染设置'
    autoSaveAt.value = clockTime()
    // 清掉「待替换」状态（待上传的 File / 上传 id 都用完了）
    modJarItems.value = []
    pendingModFiles.value = []
    clearModJars.value = false
    packInspect.clear()
    // 但已绑定的包要把清单重新解析回来：否则保存一次就得再手点一次「解析包内 jar」
    inspectBoundModPackIfNeeded()
    error.value = ''
    return 'saved'
  }
  catch (e) {
    error.value = formatError(e, '保存渲染参数失败')
    return 'failed'
  }
  finally {
    savingParams.value = false
    modJarSaving.value = false
  }
}

// ---------------------------------------------------------------------------
// 游戏版本选择：从客户端 jar 库选版本，或留空交给引擎自动判定，或手输
// ---------------------------------------------------------------------------

const clientJarVersions = ref<string[]>([])

async function refreshClientJarVersions(): Promise<void> {
  try {
    const res = await api.clientJars()
    clientJarVersions.value = (res.jars ?? []).map(j => j.version)
  }
  catch {
    // 库不可用时仅剩「自动 / 手动输入」两项，不影响其他设置
  }
}

/**
 * 版本选择状态。不能用纯 computed 双向绑定：选「手动输入」时 mcVersion 还是空串，
 * getter 会立刻塌回「自动判定」，输入框永远出不来——所以用显式状态 + 单向同步。
 */
const mcVersionChoice = ref('auto')

// mcVersion 变化（回填/手输/选版本）时同步下拉状态：空=自动，库内=对应版本，库外=手动
watch(() => updateForm.settings.mcVersion, (value) => {
  if (!value) {
    mcVersionChoice.value = 'auto'
    return
  }
  mcVersionChoice.value = clientJarVersions.value.includes(value) ? value : 'custom'
})

function onMcVersionChoiceChange(choice: unknown): void {
  if (typeof choice !== 'string') return
  // 必须先落状态：这是受控组件，不更新下拉值会弹回原选项，「手动输入」的输入框也永远不出现
  mcVersionChoice.value = choice
  if (choice === 'auto') {
    updateForm.settings.mcVersion = ''
  }
  else if (choice !== 'custom') {
    updateForm.settings.mcVersion = choice
  }
  // custom：保留当前值等手输，不在这里清空以免触发一次多余的自动保存
}

// ---------------------------------------------------------------------------
// 实例存档同步与下载（仅「从实例导入」创建的地图）：从来源实例拉取最新 world
// 覆盖本地图存档；可选开启自动同步（页面打开期间每 10 分钟一次）。
// ---------------------------------------------------------------------------

const syncing = ref(false)
const syncMessage = ref('')
const syncProgress = reactive({ stage: '', percent: -1, detail: '' })
/** 自动同步开关（按地图记忆，localStorage） */
const autoSyncEnabled = ref(false)
let autoSyncTimer: ReturnType<typeof setInterval> | null = null

/** 自动同步间隔（分钟）：面板可调，按地图记忆 */
const autoSyncMinutes = ref(10)
const AUTO_SYNC_KEY = computed(() => `voxelith-autosync-${currentMapId.value}`)
const AUTO_SYNC_MINUTES_KEY = computed(() => `voxelith-autosync-minutes-${currentMapId.value}`)

/**
 * 同步要打包的目录：优先按该图的存档根名（新版布局就是维度目录本身），
 * 打不动再退回主世界目录（老版布局的维度在 {@code world/DIM-1}、{@code world/DIM1}，
 * 实例里根本没有 {@code world_nether} 这个目录；服务端解包时会按维度把 DIM 目录归一出来）。
 */
async function packSyncZip(instanceId: string, worldName: string): Promise<File> {
  const onProgress = (progress: { stage: string, percent: number, detail: string }) => {
    syncProgress.stage = progress.stage
    syncProgress.percent = progress.percent
    syncProgress.detail = progress.detail
  }
  const rootName = worldName || 'world'
  try {
    return await packWorldZip(instanceId, [rootName], onProgress)
  }
  catch (e) {
    if (rootName === 'world') throw e
    syncProgress.stage = '按存档根名打包失败，改为打包 world 目录'
    return packWorldZip(instanceId, ['world'], onProgress)
  }
}

/** 从来源实例拉取最新 world 覆盖本地图存档 */
async function syncWorldFromInstance(): Promise<void> {
  if (!map.value?.sourceInstanceId || !currentMapId.value || syncing.value) return
  // 同步要跑几分钟，期间用户可能切到另一维度的地图：把目标 id 定在开头，
  // 落地前再核对一次——否则会把这张图的存档写到另一张图上
  const targetId = currentMapId.value
  const targetInstanceId = map.value.sourceInstanceId
  const targetWorldName = map.value.worldName || 'world'
  syncing.value = true
  syncMessage.value = ''
  try {
    // 与实例导入一致：搬运由浏览器经面板 HTTP 端点完成（插件侧不引用面板 Java 契约），
    // 新存档经宿主上传通道入库后按 fileId 覆盖本地存档。
    // 只打包本图对应维度的目录：存档包里各维度目录是并列的，同步下界地图时带上主世界既白传
    // 又会让服务端在解包时挑错存档根（世界名与包内目录名不一致）。
    // 老版存档的下界/末地在 world 里面（DIM-1/DIM1），实例里并没有 world_nether 这个目录：
    // 按存档根名打不动就退回打包 world，服务端解包时会按维度把 DIM 目录归一出来。
    const worldFile = await packSyncZip(targetInstanceId, targetWorldName)
    syncProgress.stage = '正在上传存档到文件存储'
    syncProgress.percent = -1
    syncProgress.detail = ''
    const uploaded = await props.sdk.files.uploadImage(worldFile, { module: 'voxelith-map', publicAccess: false })
    if (!uploaded.id) {
      throw new Error('存档上传后未返回文件 id')
    }
    if (currentMapId.value !== targetId) {
      // 已经切到别的维度：这份存档只属于刚才那张图，丢弃（不写盘、不误报成功）
      return
    }
    await api.replaceInstanceWorld(targetId, { worldFileId: uploaded.id })
    syncMessage.value = '已同步实例最新存档；触发渲染即可更新三维地图'
    toast.success('已同步实例最新存档')
  }
  catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    if (message.includes('实例不存在') || message.includes('not found')) {
      syncMessage.value = '来源实例已不存在（可能在 MC 面板中被删除），无法同步'
        + '；若该地图是较早版本导入的，其记录的来源实例可能有误，重新导入一次即可启用同步'
    }
    else {
      syncMessage.value = formatError(e, '同步实例存档失败')
    }
  }
  finally {
    // 同步标志是页面级的：切到另一维度时由 currentMapId 的 watcher 清掉（见下），
    // 数据安全的保证在落地前的 currentMapId 核对，不在这个标志
    syncing.value = false
  }
}

/** 下载当前地图的存档为 zip 文件 */async function downloadWorldZip(): Promise<void> {
  if (!map.value || !currentMapId.value) return
  try {
    const res = await api.downloadWorldZip(currentMapId.value)
    saveBase64Zip(res.zipBase64, `${map.value.name || currentMapId.value}-world.zip`)
  }
  catch (e) {
    toast.error(formatError(e, '存档下载失败'))
  }
}

function startAutoSync(): void {
  stopAutoSync()
  if (!autoSyncEnabled.value || !currentMapId.value) return
  const ms = Math.max(1, autoSyncMinutes.value) * 60 * 1000
  autoSyncTimer = setInterval(() => {
    if (!syncing.value) void syncWorldFromInstance()
  }, ms)
}

function stopAutoSync(): void {
  if (autoSyncTimer !== null) {
    clearInterval(autoSyncTimer)
    autoSyncTimer = null
  }
}

watch(autoSyncEnabled, (enabled) => {
  try {
    localStorage.setItem(AUTO_SYNC_KEY.value, enabled ? '1' : '0')
  } catch {}
  if (enabled) startAutoSync()
  else stopAutoSync()
})

watch(autoSyncMinutes, (minutes) => {
  try {
    localStorage.setItem(AUTO_SYNC_MINUTES_KEY.value, String(Math.max(1, minutes)))
  } catch {}
  if (autoSyncEnabled.value) startAutoSync()
})

// ---------------------------------------------------------------------------
// 自动保存：表单任何修改去抖 800ms 写回；触发渲染前会先冲刷，保证设置实时生效
// ---------------------------------------------------------------------------

const autoSaveDirty = ref(false)
const autoSaveLastResult = ref<'' | 'saved' | 'failed'>('')
/** 上一次「已落盘」的表单快照：用来算清这次到底改了哪几项 */
let savedFormSnapshot: Record<string, unknown> = {}
/** 最近一次自动保存的字段摘要与时刻（提示要给出具体操作与时间，而不是一句「已自动保存」） */
const autoSaveDetail = ref('')
const autoSaveAt = ref('')
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null
/** loadMap 回填表单时抑制一次自动保存 */
let suppressAutoSave = false

watch(updateForm, () => {
  if (suppressAutoSave || !currentMapId.value) return
  autoSaveDirty.value = true
  autoSaveLastResult.value = ''
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null
    void flushAutoSave()
  }, 800)
})

/** 立即写回未保存的修改；返回是否已确认落盘（触发渲染前必须为 true） */
async function flushAutoSave(): Promise<boolean> {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
    autoSaveTimer = null
  }
  let tries = 0
  while (autoSaveDirty.value && tries < 5) {
    tries++
    autoSaveDirty.value = false
    const result = await saveParams()
    if (result === 'failed') {
      autoSaveDirty.value = true
      autoSaveLastResult.value = 'failed'
      return false
    }
    if (result === 'skipped') {
      // 有一场保存正在进行：稍候重试，让那一场结束后补上期间的修改
      autoSaveDirty.value = true
      await new Promise(resolve => setTimeout(resolve, 300))
    }
  }
  autoSaveLastResult.value = 'saved'
  return !autoSaveDirty.value
}

function clearRegion(): void {
  updateForm.regionMinX = 0
  updateForm.regionMinZ = 0
  updateForm.regionMaxX = 0
  updateForm.regionMaxZ = 0
  updateForm.clearRegion = true
}

// ---------------------------------------------------------------------------
// 预览底图 + 框选范围
// 展示参考 voxelith 官方预览：深色面板、region(512) 网格与 r.x.z 标注、
// 光标实时世界坐标、比例尺；框选仍在图上拖拽完成。
// ---------------------------------------------------------------------------

const previewImageSrc = computed(() => {
  if (previewObjectUrl.value) return previewObjectUrl.value
  // 取图失败后不要再渲染 <img>：管理端点直连必然 400，留着只会反复触发 onerror
  if (previewImgError.value) return ''
  const meta = preview.value?.meta
  if (!meta) return ''
  return api.previewImageUrl(currentMapId.value, meta.step)
})

async function generatePreview(): Promise<void> {
  if (!currentMapId.value || previewLoading.value) return
  previewLoading.value = true
  previewImgError.value = false
  releasePreviewObjectUrl()
  try {
    await api.triggerPreview(currentMapId.value)
    pollPreview()
  }
  catch (e) {
    previewLoading.value = false
    error.value = formatError(e, '触发生成预览失败')
  }
}

function pollPreview(): void {
  if (previewTimer !== null) clearTimeout(previewTimer)
  void (async () => {
    try {
      const p = await api.preview(currentMapId.value)
      preview.value = p
      if (p.ready && p.meta) {
        previewLoading.value = false
        // 后端给出的「最低渲染高度」建议直接回填到 minY
        if (p.suggestedMinY !== null && p.suggestedMinY !== undefined) {
          updateForm.settings.minY = p.suggestedMinY
        }
        // 底图立刻走 blob 取：别等 <img> 直连失败再兜底，否则必然闪一次破图
        void loadPreviewImage()
        previewTimer = null
      }
      else if (p.error) {
        previewLoading.value = false
        previewTimer = null
      }
      else {
        previewTimer = setTimeout(pollPreview, 1500)
      }
    }
    catch {
      previewLoading.value = false
      previewTimer = null
    }
  })()
}

const selectionStyle = computed(() => {
  const meta = preview.value?.meta
  if (!meta) return null
  const x0 = Math.min(selection.x0, selection.x1)
  const x1 = Math.max(selection.x0, selection.x1)
  const y0 = Math.min(selection.y0, selection.y1)
  const y1 = Math.max(selection.y0, selection.y1)
  return {
    left: `${(x0 / meta.width) * 100}%`,
    top: `${(y0 / meta.depth) * 100}%`,
    width: `${((x1 - x0) / meta.width) * 100}%`,
    height: `${((y1 - y0) / meta.depth) * 100}%`,
  }
})

function previewPoint(e: MouseEvent): { x: number, y: number } {
  const meta = preview.value?.meta
  const el = previewImgEl.value
  if (!meta || !el) return { x: 0, y: 0 }
  const rect = el.getBoundingClientRect()
  const x = ((e.clientX - rect.left) / rect.width) * meta.width
  const y = ((e.clientY - rect.top) / rect.height) * meta.depth
  // 拖拽把手/边框时光标可能略微越出图片：夹到图内，避免框出界
  return {
    x: Math.round(Math.min(Math.max(x, 0), meta.width)),
    y: Math.round(Math.min(Math.max(y, 0), meta.depth)),
  }
}

function onPreviewDown(e: MouseEvent): void {
  const p = previewPoint(e)
  selection.x0 = p.x
  selection.y0 = p.y
  selection.x1 = p.x
  selection.y1 = p.y
  dragging.value = true
}

function onPreviewMove(e: MouseEvent): void {
  const meta = preview.value?.meta
  if (meta) {
    const p = previewPoint(e)
    previewCursor.value = { x: meta.originX + p.x * meta.step, z: meta.originZ + p.y * meta.step }
  }
  const p = previewPoint(e)
  if (resizing.value) {
    applyResize(p)
    return
  }
  if (!dragging.value) return
  selection.x1 = p.x
  selection.y1 = p.y
}

/** 正在拖拽的选框把手：四角 ne/nw/se/sw + 四边 n/s/e/w */
type ResizeKind = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'
const resizing = ref<ResizeKind | null>(null)

function onHandleDown(kind: ResizeKind, e: MouseEvent): void {
  e.stopPropagation()
  e.preventDefault()
  resizing.value = kind
  dragging.value = false
}

/** 按把手方向更新选框对应边/角（只在拖把手时调用） */
function applyResize(p: { x: number, y: number }): void {
  const kind = resizing.value
  if (!kind) return
  if (kind.includes('w')) selection.x0 = p.x
  if (kind.includes('e')) selection.x1 = p.x
  if (kind.includes('n')) selection.y0 = p.y
  if (kind.includes('s')) selection.y1 = p.y
}

function onPreviewUp(): void {
  if (resizing.value) {
    resizing.value = null
    commitRegion()
    return
  }
  if (!dragging.value) return
  dragging.value = false
  commitRegion()
}

function onPreviewLeave(): void {
  previewCursor.value = null
  onPreviewUp()
}

/** 选框四角/四边的把手位置（图片百分比坐标） */
const selectionHandles = computed(() => {
  const meta = preview.value?.meta
  if (!meta || selection.x0 === selection.x1 || selection.y0 === selection.y1) return []
  const x0 = Math.min(selection.x0, selection.x1)
  const x1 = Math.max(selection.x0, selection.x1)
  const y0 = Math.min(selection.y0, selection.y1)
  const y1 = Math.max(selection.y0, selection.y1)
  const px = (x: number) => `${(x / meta.width) * 100}%`
  const py = (y: number) => `${(y / meta.depth) * 100}%`
  return [
    { kind: 'nw', style: { left: px(x0), top: py(y0) } },
    { kind: 'n', style: { left: px((x0 + x1) / 2), top: py(y0) } },
    { kind: 'ne', style: { left: px(x1), top: py(y0) } },
    { kind: 'e', style: { left: px(x1), top: py((y0 + y1) / 2) } },
    { kind: 'se', style: { left: px(x1), top: py(y1) } },
    { kind: 's', style: { left: px((x0 + x1) / 2), top: py(y1) } },
    { kind: 'sw', style: { left: px(x0), top: py(y1) } },
    { kind: 'w', style: { left: px(x0), top: py((y0 + y1) / 2) } },
  ] as Array<{ kind: ResizeKind, style: Record<string, string> }>
})

// --- voxelith 风格覆盖层：缩放 / region·区块网格 / 坐标读取 / 比例尺 / 选区对齐 ---

const PREVIEW_REGION_BLOCKS = 512
/** Minecraft 区块 16×16 方块 */
const PREVIEW_CHUNK_BLOCKS = 16
/** 缩放上限（1 = 适配面板） */
const PREVIEW_MAX_ZOOM = 8
/** 底图适配时的最大显示高度（px） */
const PREVIEW_FIT_HEIGHT = 480

/** 当前缩放倍率，1 = 适配面板 */
const previewZoom = ref(1)
/** 网格模式：region(512) 或 区块(16) */
const previewGridMode = ref<'region' | 'chunk'>('region')
const previewViewport = ref<HTMLDivElement | null>(null)
/** 光标当前指向的世界方块坐标（离开底图置空） */
const previewCursor = ref<{ x: number, z: number } | null>(null)
/** 底图实际渲染宽度（px），用于把方块数换算成屏幕比例尺 */
const previewDisplayWidth = ref(0)
/** 视口宽度（px），用于算适配尺寸 */
const previewViewportWidth = ref(0)

function measurePreview(): void {
  previewViewportWidth.value = previewViewport.value?.clientWidth ?? 0
  previewDisplayWidth.value = previewImgEl.value?.clientWidth ?? 0
}

function setPreviewZoom(zoom: number): void {
  previewZoom.value = Math.min(PREVIEW_MAX_ZOOM, Math.max(1, zoom))
}

function zoomInPreview(): void {
  setPreviewZoom(previewZoom.value * 1.25)
}

function zoomOutPreview(): void {
  setPreviewZoom(previewZoom.value / 1.25)
}

function fitPreview(): void {
  previewZoom.value = 1
}

/** 滚轮以光标所在位置为中心缩放（放大后视口内容不跳动）；触摸板双指同理 */
function onPreviewWheel(e: WheelEvent): void {
  e.preventDefault()
  const viewport = previewViewport.value
  const old = previewZoom.value
  setPreviewZoom(old * (e.deltaY < 0 ? 1.2 : 1 / 1.2))
  if (!viewport || previewZoom.value === old) return
  const rect = viewport.getBoundingClientRect()
  // 光标下的内容点在缩放前后保持在同一屏幕位置：内容偏移 = scroll + 光标视口偏移
  const cx = e.clientX - rect.left + viewport.scrollLeft
  const cy = e.clientY - rect.top + viewport.scrollTop
  const k = previewZoom.value / old
  void nextTick(() => {
    viewport.scrollLeft = cx * k - (e.clientX - rect.left)
    viewport.scrollTop = cy * k - (e.clientY - rect.top)
  })
}

/** 底图按缩放倍率换算后的显示宽度（0 = 尚未测量，走 CSS 自适应兜底） */
const previewImgCssWidth = computed(() => {
  const meta = preview.value?.meta
  if (!meta || previewViewportWidth.value <= 0) return 0
  const fit = Math.min(previewViewportWidth.value, PREVIEW_FIT_HEIGHT * (meta.width / meta.depth))
  return Math.round(fit * previewZoom.value)
})

const previewImgStyle = computed(() => {
  if (!previewImgCssWidth.value) return undefined
  // 放大态必须解除 CSS 的 max-width/max-height，否则宽被放大、高仍被压住会把图拉变形
  return { width: `${previewImgCssWidth.value}px`, maxWidth: 'none', maxHeight: 'none', height: 'auto' }
})

// 缩放后图片渲染尺寸变化：重新量一次，比例尺才跟得上当前倍率
watch(previewImgCssWidth, () => {
  void nextTick(measurePreview)
})

/** 有正在框选（或已框选）的区域才能对齐 */
const hasSelection = computed(() => selection.x0 !== selection.x1 || selection.y0 !== selection.y1)

/** region(512) / 区块(16) 网格线与标注，坐标以底图像素为单位（= SVG viewBox 单位） */
const previewGrid = computed(() => {
  const meta = preview.value?.meta
  if (!meta) return null
  const gridBlocks = previewGridMode.value === 'region' ? PREVIEW_REGION_BLOCKS : PREVIEW_CHUNK_BLOCKS
  const endX = meta.originX + meta.width * meta.step
  const endZ = meta.originZ + meta.depth * meta.step
  const linesX: number[] = []
  const linesY: number[] = []
  const labels: Array<{ x: number, y: number, text: string }> = []
  for (let x = Math.ceil(meta.originX / gridBlocks) * gridBlocks; x <= endX; x += gridBlocks) {
    linesX.push((x - meta.originX) / meta.step)
  }
  for (let z = Math.ceil(meta.originZ / gridBlocks) * gridBlocks; z <= endZ; z += gridBlocks) {
    linesY.push((z - meta.originZ) / meta.step)
  }
  // 标注只出现在 region 模式（每个交叉点右上方 r.x.z）；区块模式密度太高，只画线不标文字
  if (gridBlocks === PREVIEW_REGION_BLOCKS) {
    const maxLabels = 400
    for (let x = Math.ceil(meta.originX / PREVIEW_REGION_BLOCKS) * PREVIEW_REGION_BLOCKS; x <= endX && labels.length < maxLabels; x += PREVIEW_REGION_BLOCKS) {
      for (let z = Math.ceil(meta.originZ / PREVIEW_REGION_BLOCKS) * PREVIEW_REGION_BLOCKS; z <= endZ && labels.length < maxLabels; z += PREVIEW_REGION_BLOCKS) {
        labels.push({
          x: (x - meta.originX) / meta.step,
          y: (z - meta.originZ) / meta.step,
          text: `r.${Math.floor(x / PREVIEW_REGION_BLOCKS)}.${Math.floor(z / PREVIEW_REGION_BLOCKS)}`,
        })
      }
    }
  }
  return {
    linesX,
    linesY,
    labels,
    fontSize: Math.max(8, Math.round(meta.width / 70)),
  }
})

const previewReadout = computed(() => {
  const meta = preview.value?.meta
  if (!meta) return ''
  if (dragging.value) {
    const w = Math.abs(selection.x1 - selection.x0) * meta.step
    const h = Math.abs(selection.y1 - selection.y0) * meta.step
    return `选区 ${Math.round(w)} × ${Math.round(h)} 方块`
  }
  if (previewCursor.value) {
    return `X ${previewCursor.value.x} · Z ${previewCursor.value.z}`
  }
  return '把光标移到地图上读取坐标'
})

/** 比例尺：选一个能画到至少 40px 的整方块数（256 → 128 → 64 → 32） */
const previewScale = computed(() => {
  const meta = preview.value?.meta
  if (!meta || previewDisplayWidth.value <= 0) return null
  const pxPerBlock = previewDisplayWidth.value / (meta.width * meta.step)
  for (const blocks of [256, 128, 64, 32]) {
    const width = blocks * pxPerBlock
    if (width >= 40) {
      return { width, label: `${blocks} 方块` }
    }
  }
  return { width: 32 * pxPerBlock, label: '32 方块' }
})

/**
 * 按后端公式换算框选像素 → 世界方块坐标：
 *   blockX = meta.originX + pixelX * meta.step
 *   blockZ = meta.originZ + pixelY * meta.step
 * 写入渲染参数表单的 region 字段；带不上鉴权令牌时退化为 blob 取图。
 */
function commitRegion(): void {
  const meta = preview.value?.meta
  if (!meta) return
  const px0 = Math.min(selection.x0, selection.x1)
  const px1 = Math.max(selection.x0, selection.x1)
  const py0 = Math.min(selection.y0, selection.y1)
  const py1 = Math.max(selection.y0, selection.y1)
  updateForm.clearRegion = false
  updateForm.regionMinX = meta.originX + px0 * meta.step
  updateForm.regionMinZ = meta.originZ + py0 * meta.step
  updateForm.regionMaxX = meta.originX + px1 * meta.step
  updateForm.regionMaxZ = meta.originZ + py1 * meta.step
}

/**
 * 把当前框选区域向外扩张、对齐到 region(512) 或区块(16) 边界，并回填渲染范围字段。
 * 对齐渲染范围可以避免多渲染出边缘的半个 region（各 512 方块）。
 */
function alignSelection(gridBlocks: number): void {
  const meta = preview.value?.meta
  if (!meta || !hasSelection.value) return
  const px0 = Math.min(selection.x0, selection.x1)
  const px1 = Math.max(selection.x0, selection.x1)
  const py0 = Math.min(selection.y0, selection.y1)
  const py1 = Math.max(selection.y0, selection.y1)
  const worldMinX = meta.originX + px0 * meta.step
  const worldMaxX = meta.originX + px1 * meta.step
  const worldMinZ = meta.originZ + py0 * meta.step
  const worldMaxZ = meta.originZ + py1 * meta.step
  const minX = Math.floor(worldMinX / gridBlocks) * gridBlocks
  const maxX = Math.ceil(worldMaxX / gridBlocks) * gridBlocks
  const minZ = Math.floor(worldMinZ / gridBlocks) * gridBlocks
  const maxZ = Math.ceil(worldMaxZ / gridBlocks) * gridBlocks
  updateForm.clearRegion = false
  updateForm.regionMinX = minX
  updateForm.regionMinZ = minZ
  updateForm.regionMaxX = maxX
  updateForm.regionMaxZ = maxZ
  // 同步框选可视化，让底图上直接看到对齐后的范围
  selection.x0 = (minX - meta.originX) / meta.step
  selection.x1 = (maxX - meta.originX) / meta.step
  selection.y0 = (minZ - meta.originZ) / meta.step
  selection.y1 = (maxZ - meta.originZ) / meta.step
}

/** 释放上一个 object URL（重复取图与组件卸载都必须调用，否则泄漏）。 */
function releasePreviewObjectUrl(): void {
  if (previewObjectUrl.value) {
    URL.revokeObjectURL(previewObjectUrl.value)
    previewObjectUrl.value = null
  }
}

/**
 * 取预览底图。
 *
 * 不能把管理端 URL 直接交给 `<img>`：该端点要 plugin:voxelith-map:manage，
 * 而 `<img>` 带不上 Bearer 令牌，直连只会拿到「无此插件权限」（400）并渲染成破图。
 * 所以统一走 sdk.http.blob()（补鉴权）取二进制，再转 object URL 喂给 `<img>`。
 */
async function loadPreviewImage(force = false): Promise<void> {
  const meta = preview.value?.meta
  if (!meta || previewImgPending || !currentMapId.value) return
  if (previewObjectUrl.value && !force) return
  previewImgPending = true
  try {
    const step = encodeURIComponent(String(meta.step))
    const path = `/admin/maps/${encodeURIComponent(currentMapId.value)}/preview.png?step=${step}`
    const url = await fetchImageObjectUrl(props.sdk, path)
    releasePreviewObjectUrl()
    previewObjectUrl.value = url
    previewImgError.value = false
  }
  catch {
    previewImgError.value = true
  }
  finally {
    previewImgPending = false
  }
}

/**
 * `<img>` 兜底。
 *
 * src 还是直连 URL 时（blob 尚未就绪）必然失败，这里换个姿势再取一次；
 * 但如果 object URL 已经生成却仍渲染不出来，说明取回来的内容本身不是图片，
 * 此时再重取只会无限循环 —— 直接收敛成可见的失败态。
 */
function onPreviewImgError(): void {
  if (previewObjectUrl.value) {
    releasePreviewObjectUrl()
    previewImgError.value = true
    return
  }
  void loadPreviewImage(true)
}

// ---------------------------------------------------------------------------
// 渲染任务
// ---------------------------------------------------------------------------

async function triggerRender(): Promise<void> {
  if (!currentMapId.value) return
  // 渲染前冲刷未落盘的设置修改：保证「触发渲染时实时生效」
  if (!await flushAutoSave()) {
    error.value = '渲染设置自动保存失败，请检查后重试'
    return
  }
  try {
    await api.render(currentMapId.value)
    await taskStream.refresh(true)
  }
  catch (e) {
    error.value = formatError(e, '触发渲染失败')
  }
}

async function cancelActiveTask(): Promise<void> {
  const task = taskStream.activeTask.value
  if (!task) return
  try {
    const res = await api.cancelTask(task.id)
    if (!res.canceled) {
      // 后端返回 canceled=false：任务其实已经结束（或服务重启过），如实告知
      toast.warning('任务已结束或服务已重启，无需停止')
    }
    await taskStream.refresh(true)
  }
  catch (e) {
    error.value = formatError(e, '取消任务失败')
  }
}

function taskError(task: RenderTask): string {
  return task.error || task.message || '渲染失败'
}

function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 12)}…` : id
}

/** 瓦片计数只在真的有值时展示：老任务文档里没有计数（恒 0/0），不能把假的 0/0 亮出来。 */
function tileText(task: RenderTask): string {
  return task.totalTiles > 0 ? ` · ${task.completedTiles} / ${task.totalTiles}` : ''
}

function openErrorDetail(task: RenderTask): void {
  selectedTaskError.value = task
  errorDrawerOpen.value = true
}

/** 正在下载完整日志的任务 id */
const taskLogDownloading = ref('')

/** 抽屉里只有日志尾部（4000 字），完整日志按需下载 */
async function downloadTaskLogFile(task: RenderTask): Promise<void> {
  if (taskLogDownloading.value) return
  taskLogDownloading.value = task.id
  try {
    await downloadTaskLog(props.sdk, task.id)
  }
  catch (e) {
    toast.error(formatError(e, '完整日志获取失败'))
  }
  finally {
    taskLogDownloading.value = ''
  }
}

/** 抽屉标题：任务态看状态（失败=失败详情，其余=任务日志），地图级错误单独命名 */
const errorDrawerTitle = computed(() => {
  if (!selectedTaskError.value) return '地图错误详情'
  return selectedTaskError.value.state === 'FAILED' || selectedTaskError.value.state === 'CANCELED'
    ? '渲染失败详情'
    : '渲染任务日志'
})

function openMapErrorDetail(): void {
  if (!map.value?.lastError) return
  errorDrawerOpen.value = true
  selectedTaskError.value = null
}

function backToList(): void {
  void router.push('/platform/plugins/voxelith-map/maps')
}

// ---------------------------------------------------------------------------
// 页头动作：打开公开查看器 / 删除地图
// ---------------------------------------------------------------------------

const modal = useYdModal()
const deletingMap = ref(false)

function openPublicViewer(): void {
  if (!map.value || map.value.state !== 'READY') return
  window.open(`/platform/plugins/voxelith-map/viewer?map=${encodeURIComponent(map.value.id)}`, '_blank')
}

function confirmDeleteMap(): void {
  const target = map.value
  if (!target || isBusy(target.state)) return
  modal.confirm({
    title: '删除地图',
    content: `确认删除「${target.name}」吗？该地图的全部瓦片数据将一并删除，且不可恢复。`
      + '\n删除将在服务端后台执行，可在「任务中心」查看结果与日志。',
    onConfirm: async () => {
      deletingMap.value = true
      try {
        // 删除作业在服务端执行（回收产物/存档/记录），可在任务中心看结果与日志
        await api.startDeleteJob(target.id)
        toast.success(`「${target.name}」的删除任务已开始（服务端执行）`)
        backToList()
      }
      catch (e) {
        toast.error(formatError(e, '删除失败'))
      }
      finally {
        deletingMap.value = false
      }
    },
  })
}

// ---------------------------------------------------------------------------
// 全景图烘焙
// ---------------------------------------------------------------------------

const panorama = ref<PanoramaStatus | null>(null)
const panoramaLoading = ref(false)
const panoramaError = ref('')
/** 重新烘焙成功后自增：公开端按缓存策略回发 PNG，需要变一个查询参数才能拿到新图 */
const panoramaRevision = ref(0)
let panoramaTimer: ReturnType<typeof setTimeout> | null = null

const panoramaForm = reactive({
  width: 3840,
  height: 2160,
  yaw: 0,
  pitch: 45,
  fov: 60,
  distance: 1.25,
})

const panoramaStateMeta = computed(() => PANORAMA_STATE_META[panorama.value?.state ?? 'IDLE'])
const panoramaRunning = computed(() => panorama.value?.state === 'RUNNING')
const panoramaUrl = computed(() => {
  if (!currentMapId.value || !panorama.value?.generated) return ''
  return publicApi.panoramaUrl(currentMapId.value, panoramaRevision.value)
})

async function loadPanorama(): Promise<void> {
  if (!currentMapId.value) return
  panoramaLoading.value = true
  try {
    const hadPanorama = panorama.value?.generated === true
    const next = await api.panorama(currentMapId.value)
    panorama.value = next
    panoramaError.value = ''
    // 只在「本来没有、这轮有了」时破缓存：否则每次轮询都会重新下载整张图
    if (next.state === 'SUCCEEDED' && next.generated && !hadPanorama) {
      panoramaRevision.value += 1
    }
    if (next.state === 'RUNNING') {
      schedulePanoramaPoll()
    }
  }
  catch (e) {
    panoramaError.value = formatError(e, '全景图状态加载失败')
  }
  finally {
    panoramaLoading.value = false
  }
}

function schedulePanoramaPoll(): void {
  if (panoramaTimer !== null) clearTimeout(panoramaTimer)
  panoramaTimer = setTimeout(() => {
    panoramaTimer = null
    void loadPanorama()
  }, 2000)
}

async function triggerPanorama(): Promise<void> {
  if (!currentMapId.value || panoramaRunning.value) return
  panoramaError.value = ''
  try {
    // 后端起的是异步任务，这里拿到的就是 RUNNING 态，进度靠轮询跟上
    panorama.value = await api.triggerPanorama(currentMapId.value, { ...panoramaForm })
    schedulePanoramaPoll()
  }
  catch (e) {
    panoramaError.value = formatError(e, '触发全景图烘焙失败')
  }
}

// ---------------------------------------------------------------------------
// 分布式分片 worker
// ---------------------------------------------------------------------------

const shard = ref<ShardWorkerStatus | null>(null)
const shardLoading = ref(false)
const shardAction = ref(false)
const shardError = ref('')
const shardNote = ref('')
let shardTimer: ReturnType<typeof setTimeout> | null = null

const shardForm = reactive({ workerId: '', threads: 1, leaseMinutes: 30, idleMinutes: 10 })

const shardStateMeta = computed(() => SHARD_WORKER_STATE_META[shard.value?.state ?? 'STOPPED'])
const shardAlive = computed(() => shard.value?.alive === true)

/**
 * 状态未加载时的兜底文案。分片队列的写入方在 Voxelith 自己的管线侧
 * （RunPipelineUseCase 的队列模式），没有命令行入口；插件只托管 worker 进程。
 * 这条能力边界必须显式告诉管理员，否则会误以为「启动了就一定在渲染」。
 */
const WORKER_QUEUE_HINT =
  '本插件只托管分片 worker 进程，不生产分片队列：队列须由外部工具或其它 worker 写入共享队列目录，'
  + '队列为空时 worker 会在空闲超时后自行退出。'

async function loadShard(): Promise<void> {
  if (!currentMapId.value) return
  shardLoading.value = true
  try {
    shard.value = await api.shardWorker(currentMapId.value)
    shardError.value = ''
    if (shard.value.alive) {
      scheduleShardPoll()
    }
  }
  catch (e) {
    shardError.value = formatError(e, '分片 worker 状态加载失败')
  }
  finally {
    shardLoading.value = false
  }
}

function scheduleShardPoll(): void {
  if (shardTimer !== null) clearTimeout(shardTimer)
  shardTimer = setTimeout(() => {
    shardTimer = null
    void loadShard()
  }, 5000)
}

async function startShard(): Promise<void> {
  if (!currentMapId.value || shardAlive.value || shardAction.value) return
  shardAction.value = true
  shardError.value = ''
  shardNote.value = ''
  try {
    shard.value = await api.startShardWorker(currentMapId.value, {
      workerId: shardForm.workerId.trim() || undefined,
      threads: shardForm.threads,
      leaseMinutes: shardForm.leaseMinutes,
      idleMinutes: shardForm.idleMinutes,
    })
    scheduleShardPoll()
  }
  catch (e) {
    shardError.value = formatError(e, '启动分片 worker 失败')
  }
  finally {
    shardAction.value = false
  }
}

async function stopShard(): Promise<void> {
  if (!currentMapId.value || !shardAlive.value || shardAction.value) return
  shardAction.value = true
  shardError.value = ''
  try {
    shard.value = await api.stopShardWorker(currentMapId.value)
    if (shardTimer !== null) {
      clearTimeout(shardTimer)
      shardTimer = null
    }
    shardNote.value = `已停止，本次回传产物 ${shard.value.syncedTiles} 个`
  }
  catch (e) {
    shardError.value = formatError(e, '停止分片 worker 失败')
  }
  finally {
    shardAction.value = false
  }
}

/** 目录很长且要贴到别的机器上，给个复制按钮；不安全上下文下退回提示手动复制。 */
async function copyDir(text: string, label: string): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.clipboard) {
    shardNote.value = `${label}无法自动复制，请手动选择：${text}`
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    shardNote.value = `${label}已复制`
  }
  catch {
    shardNote.value = `${label}复制失败，请手动选择`
  }
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(() => {
  void loadMap()
  taskStream.start()
  // 恢复自动同步偏好（按地图记忆）
  try {
    autoSyncEnabled.value = localStorage.getItem(AUTO_SYNC_KEY.value) === '1'
    const saved = localStorage.getItem(AUTO_SYNC_MINUTES_KEY.value)
    if (saved) autoSyncMinutes.value = Math.max(1, parseInt(saved, 10) || 10)
  } catch {}
  if (autoSyncEnabled.value) startAutoSync()
  // 次要状态延后到首屏之后：与地图详情主体渲染争抢会拖慢页面可交互时间
  const idle = (fn: () => void) => {
    const w = window as unknown as { requestIdleCallback?: (cb: () => void) => number }
    if (typeof w.requestIdleCallback === 'function') w.requestIdleCallback(() => fn())
    else window.setTimeout(fn, 200)
  }
  idle(() => {
    void loadPanorama()
    void loadShard()
    void refreshClientJarVersions()
  })
  window.addEventListener('resize', measurePreview)
})

onBeforeUnmount(() => {
  stopAutoSync()
  window.removeEventListener('resize', measurePreview)
  if (previewTimer !== null) clearTimeout(previewTimer)
  if (panoramaTimer !== null) clearTimeout(panoramaTimer)
  if (shardTimer !== null) clearTimeout(shardTimer)
  releasePreviewObjectUrl()
  taskStream.stop()
  viewer.value?.dispose()
  viewer.value = null
})
</script>

<template>
  <YdPageHeader class="mb-0">
    <template #title>
      <span class="flex flex-wrap items-center gap-2">
        {{ map && map.name ? `地图详情：${map.name}` : '地图详情' }}
        <YdTag v-if="map" :variant="mapStateTag(map.state).variant">
          {{ mapStateTag(map.state).label }}
        </YdTag>
      </span>
    </template>
    <YdButton size="sm" variant="outline" @click="backToList">
      <YdIcon name="i-mdi:arrow-left" />
      返回列表
    </YdButton>
    <YdButton
      size="sm"
      variant="outline"
      :disabled="!map || map.state !== 'READY'"
      @click="openPublicViewer"
    >
      <YdIcon name="i-mdi:open-in-new" />
      打开查看器
    </YdButton>
    <YdButton
      size="sm"
      variant="destructive"
      :disabled="!map || isBusy(map.state)"
      :loading="deletingMap"
      @click="confirmDeleteMap"
    >
      <YdIcon name="i-mdi:delete-outline" />
      删除地图
    </YdButton>
  </YdPageHeader>

  <YdPageMain>
    <div v-if="error" class="py-12 text-center text-muted-foreground">{{ error }}</div>
    <div v-else-if="loading && !map" class="py-12 text-center text-muted-foreground">加载中…</div>

    <template v-else-if="map">
      <!-- 基本信息 -->
      <YdCard class="mb-4 p-4">
        <div class="grid grid-cols-2 gap-x-8 gap-y-2 text-sm md:grid-cols-4">
          <div class="min-w-0"><span class="text-muted-foreground">地图 ID：</span><span class="break-all">{{ map.id }}</span></div>
          <div><span class="text-muted-foreground">维度：</span>{{ dimensionLabel(map.dimension) }}</div>
          <div>
            <span class="text-muted-foreground">状态：</span>
            <YdTag :variant="mapStateTag(map.state).variant">{{ mapStateTag(map.state).label }}</YdTag>
          </div>
          <div><span class="text-muted-foreground">存档名：</span>{{ map.worldName || '—' }}</div>
          <!-- 同一世界的维度：始终占一行——切不动时也要看得出「有几个维度、为什么没有同伴」，
               否则用户只会看到「和以前一样、没有任何变化」 -->
          <div class="col-span-2 flex flex-wrap items-center gap-x-2 gap-y-2 md:col-span-4">
            <span class="text-muted-foreground">同一世界的维度：</span>
            <template v-if="dimensionChoices.length">
              <div
                v-for="choice in dimensionChoices"
                :key="choice.id"
                class="flex items-center gap-1"
              >
                <YdButton
                  size="sm"
                  :variant="choice.current ? 'default' : 'outline'"
                  :title="choice.current ? `当前维度：${choice.name}` : `切换到${choice.label}：${choice.name}`"
                  @click="switchDimension(choice.id)"
                >
                  {{ choice.label }}
                  <span
                    v-if="dimensionStateLabel(choice)"
                    class="text-xs font-normal opacity-80"
                  >{{ dimensionStateLabel(choice) }}</span>
                </YdButton>
                <!-- 当前维度用页面上原有的「开始渲染 / 下载存档」，这里只给别的维度 -->
                <template v-if="!choice.current">
                  <YdButton
                    size="sm"
                    variant="ghost"
                    title="更新：重新渲染该维度"
                    :loading="dimensionActionId === choice.id"
                    :disabled="isBusy(choice.state as MapAdmin['state'])"
                    @click="renderDimension(choice)"
                  >
                    <YdIcon name="i-mdi:refresh" />
                  </YdButton>
                  <YdButton
                    size="sm"
                    variant="ghost"
                    title="下载：该维度的存档压缩包"
                    :disabled="dimensionActionId === choice.id"
                    @click="downloadDimensionWorld(choice)"
                  >
                    <YdIcon name="i-mdi:download" />
                  </YdButton>
                </template>
              </div>
            </template>
            <span v-else class="text-muted-foreground">读取中…</span>
            <span v-if="dimensionError" class="text-xs text-destructive">{{ dimensionError }}</span>
            <span
              v-else-if="dimensionChoices.length <= 1"
              class="text-xs text-muted-foreground"
            >
              这个地图目前没有同伴维度：从 MC 面板实例导入时各维度会自动归为一组，同一实例分几次导入的地图也归一组
            </span>
          </div>
          <div class="min-w-0">
            <span class="text-muted-foreground">模组：</span>
            <template v-if="map.modsPackFileId || map.modJars?.length">
              <!-- 模组包：一个 zip，渲染前由后端解包（整包只占一个存储对象） -->
              <YdTag v-if="map.modsPackFileId" class="mr-1" variant="secondary" title="渲染前解包成模组 jar">
                包：{{ map.modsPackName || 'mods.zip' }}
              </YdTag>
              <YdTag
                v-for="jar in map.modJars"
                :key="jar.fileId"
                class="mr-1"
                variant="secondary"
              >{{ jar.name || jar.fileId }}</YdTag>
            </template>
            <span v-else class="text-muted-foreground">无（纯原版）</span>
          </div>
          <div><span class="text-muted-foreground">规模：</span>{{ map.scaleDescription || '—' }}</div>
          <div><span class="text-muted-foreground">创建：</span>{{ formatTime(map.createdAt) }}</div>
          <div><span class="text-muted-foreground">更新：</span>{{ formatTime(map.updatedAt) }}</div>
          <div
            v-if="map.sourceInstanceId"
            class="col-span-2 flex flex-wrap items-center gap-2 md:col-span-4"
          >
            <span class="text-muted-foreground">实例存档：</span>
            <YdButton size="sm" variant="outline" :disabled="syncing" @click="syncWorldFromInstance">
              同步最新存档
            </YdButton>
            <YdButton size="sm" variant="outline" :disabled="syncing" @click="downloadWorldZip">
              下载存档 zip
            </YdButton>
            <label class="flex items-center gap-1.5 text-xs">
              <YdSwitch v-model="autoSyncEnabled" />
              每
              <YdNumberField v-model="autoSyncMinutes" :min="1" :max="1440" :step="1" class="w-16" />
              分钟自动同步
            </label>
            <span
              v-if="syncing"
              class="text-xs text-muted-foreground"
            >{{ syncProgress.stage || '同步中…' }}{{ syncProgress.detail ? ` · ${syncProgress.detail}` : '' }}</span>
            <span
              v-else-if="syncMessage"
              class="text-xs"
              :class="syncMessage.includes('失败') ? 'text-destructive' : 'text-muted-foreground'"
            >{{ syncMessage }}</span>
          </div>
          <div
            v-if="map.lastError"
            class="col-span-2 flex min-w-0 items-center gap-1.5 text-destructive"
          >
            <span class="shrink-0">错误：</span>
            <span class="min-w-0 flex-1 truncate" :title="map.lastError">{{ map.lastError }}</span>
            <YdButton size="sm" variant="ghost" title="查看完整错误" @click="openMapErrorDetail">
              <YdIcon name="i-mdi:text-box-search-outline" />
            </YdButton>
          </div>
        </div>
      </YdCard>

      <!-- 第 1 步：渲染设置 -->
      <YdCard class="mb-4 p-4">
        <h3 class="mb-3 text-base font-semibold">① 渲染设置</h3>
        <div class="grid gap-4 md:grid-cols-2">
          <!-- 地图名与存档名合并为一个（与新建弹窗同口径） -->
          <label class="grid gap-1.5">
            <span class="text-sm">存档名称</span>
            <YdInput v-model="updateForm.name" />
            <span class="text-xs text-muted-foreground">地图名与存档名合并：列表、查看器与同步都用它</span>
          </label>
          <label class="grid gap-1.5">
            <span class="text-sm">存档维度</span>
            <YdSelect v-model="updateForm.dimension" :options="DIMENSION_OPTIONS" />
            <span class="text-xs text-muted-foreground">维度跟随这份存档</span>
          </label>
          <label class="grid gap-1.5">
            <span class="text-sm">最低渲染高度 (minY)</span>
            <YdNumberField v-model="updateForm.settings.minY" :min="-2048" :max="2048" :step="1" />
          </label>
        </div>

        <!-- 高级参数：默认收起 -->
        <CollapsibleSection
          title="高级参数"
          subtitle="游戏版本 / loader / LOD 图集 / meshopt / 跳过收割"
          storage-key="adv-params"
          class="mt-3"
        >
          <div class="grid gap-4 md:grid-cols-2">
            <label class="grid gap-1.5">
              <span class="text-sm">游戏版本 (mcVersion)</span>
              <YdSelect
                :model-value="mcVersionChoice"
                :options="[
                  { label: '自动判定（留空）', value: 'auto' },
                  ...clientJarVersions.map(v => ({ label: `jar 库：${v}`, value: v })),
                  { label: '手动输入…', value: 'custom' },
                ]"
                @update:model-value="onMcVersionChoiceChange"
              />
              <YdInput
                v-if="mcVersionChoice === 'custom'"
                v-model="updateForm.settings.mcVersion"
                placeholder="手动输入版本号，如 1.21.8"
              />
              <span class="text-xs text-muted-foreground">{{ MC_VERSION_HINT }}</span>
            </label>
            <label class="grid gap-1.5">
              <span class="text-sm">loaderVersion</span>
              <YdInput v-model="updateForm.settings.loaderVersion" />
            </label>
            <label class="grid gap-1.5">
              <span class="text-sm">单次渲染区块上限 (max-chunks，0 = 不限)</span>
              <YdNumberField v-model="updateForm.settings.maxChunks" :min="0" :step="256" />
              <span class="text-xs text-muted-foreground">范围超出上限时引擎直接拒绝渲染；配合缩小范围控制单次规模</span>
            </label>
            <label class="grid gap-1.5">
              <span class="text-sm">批次大小 (batch-chunks)</span>
              <YdNumberField v-model="updateForm.settings.batchChunks" :min="64" :step="128" />
              <span class="text-xs text-muted-foreground">渲染报内存不足时调小（如 512 / 1024）分批渲染，降低子进程堆压力</span>
            </label>
            <label class="flex items-center gap-2 text-sm">
              <YdSwitch v-model="updateForm.settings.lodAtlas" />LOD 图集
            </label>
            <label class="flex items-center gap-2 text-sm">
              <YdSwitch v-model="updateForm.settings.meshopt" />meshopt 压缩
            </label>
            <label class="flex items-center gap-2 text-sm">
              <YdSwitch v-model="updateForm.settings.skipHarvest" />跳过收割
            </label>
          </div>
        </CollapsibleSection>

        <!-- 模组：整体替换语义 -->
        <CollapsibleSection
          title="模组"
          :subtitle="modsSubtitle"
          storage-key="mod-jars"
          class="mt-3"
        >
          <div class="mb-2 flex flex-wrap items-center gap-1">
            <!-- 模组包：整包只占一个存储对象，渲染时由后端解包成 jar -->
            <YdTag v-if="map.modsPackFileId" variant="secondary" title="渲染前解包成模组 jar">
              包：{{ map.modsPackName || 'mods.zip' }}
            </YdTag>
            <template v-if="map.modsPackFileId">
              <YdButton
                size="sm"
                variant="outline"
                :loading="packInspect.loading.value"
                @click="inspectBoundModPack"
              >
                {{ packInspect.listing.value ? '重新解析包内 jar' : '解析包内 jar' }}
              </YdButton>
              <YdButton size="sm" variant="outline" @click="removeModPack">
                删除模组包
              </YdButton>
            </template>
            <YdTag
              v-for="jar in map.modJars ?? []"
              :key="jar.fileId"
              variant="secondary"
            >{{ jar.name || jar.fileId }}</YdTag>
          </div>
          <div v-if="packInspect.error.value" class="mb-2 text-xs text-destructive">{{ packInspect.error.value }}</div>
          <div v-if="packInspect.loading.value" class="mb-2 grid gap-1">
            <div class="flex items-center gap-2 text-xs">
              <YdIcon name="i-mdi:progress-upload" />
              <span>
                {{ packInspect.stage.value === 'uploading' ? '正在上传模组包…' : '正在解析包内 jar…' }}
                已等待 {{ packInspect.elapsedSeconds.value }} 秒
              </span>
            </div>
            <span class="text-xs text-muted-foreground">
              解析要读一遍整个包（zip 目录在包尾，服务端必须先把包落到本地），大包会慢；
              完成后即可看到包内 jar 列表，期间不能触发渲染
            </span>
          </div>
          <div v-if="packInspect.listing.value" class="mb-2 rounded-md border border-border p-3">
            <div class="mb-1 flex flex-wrap items-center gap-2 text-xs">
              <span class="font-medium">包内模组 jar：{{ packInspect.listing.value.jarCount }} 个</span>
              <span class="text-muted-foreground">解压后合计 {{ mb(packInspect.listing.value.totalBytes) }}</span>
              <YdTag v-if="packInspect.pendingFileId.value" variant="secondary">待保存</YdTag>
              <span v-if="packInspect.listing.value.truncated" class="text-muted-foreground">
                （仅列出前 {{ packInspect.listing.value.jars.length }} 个）
              </span>
            </div>
            <div
              v-if="packInspect.listing.value.jarCount"
              class="grid max-h-40 gap-1 overflow-y-auto pr-1 text-xs"
            >
              <div
                v-for="jar in packInspect.listing.value.jars"
                :key="jar.name"
                class="flex items-center justify-between gap-2"
              >
                <span class="min-w-0 truncate font-mono" :title="jar.name">{{ jar.name }}</span>
                <span class="shrink-0 text-muted-foreground">{{ mb(jar.sizeBytes) }}</span>
              </div>
            </div>
            <div v-else class="text-xs text-muted-foreground">
              包内没有 jar 文件：请确认上传的是 mods 目录的 zip 包
            </div>
          </div>
          <YdFileUpload
            v-model="modJarItems"
            multiple
            :disabled="savingParams"
            :before-upload="isModFile"
            :http-request="selectModJar"
            description="可一次多选模组 JAR，或上传一个 mods 目录的 zip 包（整包登记、渲染前解包，不做逐个 jar 落库）；保存后整体替换，下次渲染生效"
          />
          <label class="mt-2 flex items-center gap-2 text-sm">
            <YdSwitch v-model="clearModJars" />清空全部模组（恢复纯原版渲染）
          </label>
          <p class="mt-1 text-xs text-muted-foreground">
            增删模组后需要重新渲染才会生效；首次渲染新模组组合时会多一次模型采集（约 1 分钟）。
          </p>
        </CollapsibleSection>

        <!-- 范围框选：先生成预览底图，再在图上拖拽框选 -->
        <CollapsibleSection
          title="渲染范围（底图框选）"
          :subtitle="updateForm.clearRegion ? '未设置（渲染整张图）' : `X ${updateForm.regionMinX} ~ ${updateForm.regionMaxX} · Z ${updateForm.regionMinZ} ~ ${updateForm.regionMaxZ}`"
          storage-key="render-region"
          class="mt-3"
        >
          <div class="mb-2 flex flex-wrap items-center gap-2">
            <YdButton size="sm" :loading="previewLoading" @click="generatePreview">
              生成预览底图
            </YdButton>
            <span class="text-xs text-muted-foreground">
              生成后在底图上按住鼠标拖拽框选渲染范围；框选完成后会按公式换算为世界方块坐标并回填下方字段
            </span>
          </div>

          <div v-if="preview && !preview.ready && !preview.error" class="text-sm text-muted-foreground">
            预览生成中 {{ preview.percent }}%（{{ preview.done }}/{{ preview.total }}）…
          </div>
          <div v-else-if="preview && preview.error" class="text-sm text-destructive">
            预览失败：{{ preview.error }}
          </div>
          <div
            v-if="previewImgError"
            class="mt-2 flex flex-wrap items-center gap-2 text-sm text-destructive"
          >
            <span>底图加载失败：取图接口没有返回图片内容（预览本身已生成，可重试）。</span>
            <YdButton size="sm" variant="outline" @click="loadPreviewImage(true)">重试</YdButton>
          </div>

          <div v-if="previewImageSrc" class="voxelith-preview-panel">
            <div
              ref="previewViewport"
              class="voxelith-preview-viewport"
              @wheel="onPreviewWheel"
              @mousemove="onPreviewMove"
              @mouseup="onPreviewUp"
              @mouseleave="onPreviewLeave"
            >
              <div class="voxelith-preview-stage">
                <img
                  ref="previewImgEl"
                  :src="previewImageSrc"
                  class="voxelith-preview-img"
                  :style="previewImgStyle"
                  draggable="false"
                  @mousedown="onPreviewDown"
                  @error="onPreviewImgError"
                  @load="measurePreview"
                />
                <svg
                  v-if="previewGrid && preview?.meta"
                  class="voxelith-preview-grid"
                  :class="{ 'voxelith-chunk': previewGridMode === 'chunk' }"
                  :viewBox="`0 0 ${preview.meta.width} ${preview.meta.depth}`"
                  preserveAspectRatio="none"
                >
                  <line
                    v-for="(x, index) in previewGrid.linesX"
                    :key="`x${index}`"
                    :x1="x" y1="0" :x2="x" :y2="preview.meta.depth"
                  />
                  <line
                    v-for="(y, index) in previewGrid.linesY"
                    :key="`y${index}`"
                    x1="0" :y1="y" :x2="preview.meta.width" :y2="y"
                  />
                  <text
                    v-for="(label, index) in previewGrid.labels"
                    :key="`t${index}`"
                    :x="label.x + previewGrid.fontSize * 0.4"
                    :y="label.y - previewGrid.fontSize * 0.4"
                    :font-size="previewGrid.fontSize"
                  >{{ label.text }}</text>
                </svg>
                <div v-if="selectionStyle" class="voxelith-preview-selection" :style="selectionStyle" />
                <template v-if="selectionStyle">
                  <div
                    v-for="handle in selectionHandles"
                    :key="handle.kind"
                    class="voxelith-preview-handle"
                    :class="`voxelith-handle-${handle.kind}`"
                    :style="handle.style"
                    @mousedown="onHandleDown(handle.kind, $event)"
                  />
                </template>
              </div>
            </div>
            <div class="voxelith-preview-readout">{{ previewReadout }}</div>
            <div v-if="previewScale" class="voxelith-preview-scale">
              <span class="voxelith-preview-scale-bar" :style="{ width: `${previewScale.width}px` }" />
              <span>{{ previewScale.label }}</span>
            </div>
          </div>

          <!-- 缩放 / 网格 / 对齐工具条（布局参考 voxelith 预览） -->
          <div v-if="previewImageSrc" class="mt-2 flex flex-wrap items-center gap-2">
            <YdButton size="sm" variant="outline" title="放大（或直接滚动滚轮）" @click="zoomInPreview">
              <YdIcon name="i-mdi:plus" />
            </YdButton>
            <YdButton size="sm" variant="outline" title="缩小（或直接滚动滚轮）" :disabled="previewZoom <= 1" @click="zoomOutPreview">
              <YdIcon name="i-mdi:minus" />
            </YdButton>
            <YdButton size="sm" variant="outline" title="适应面板" :disabled="previewZoom <= 1" @click="fitPreview">
              <YdIcon name="i-mdi:fit-to-screen-outline" />
              适应
            </YdButton>
            <span class="text-xs text-muted-foreground">{{ Math.round(previewZoom * 100) }}%</span>
            <span class="mx-1 h-4 w-px bg-border" />
            <YdButton
              size="sm"
              :variant="previewGridMode === 'region' ? 'default' : 'outline'"
              @click="previewGridMode = 'region'"
            >region</YdButton>
            <YdButton
              size="sm"
              :variant="previewGridMode === 'chunk' ? 'default' : 'outline'"
              @click="previewGridMode = 'chunk'"
            >区块</YdButton>
            <span class="mx-1 h-4 w-px bg-border" />
            <YdButton size="sm" variant="outline" :disabled="!hasSelection" @click="alignSelection(PREVIEW_REGION_BLOCKS)">
              对齐 region
            </YdButton>
            <YdButton size="sm" variant="outline" :disabled="!hasSelection" @click="alignSelection(PREVIEW_CHUNK_BLOCKS)">
              对齐区块
            </YdButton>
          </div>

          <!-- YdNumberField 内置 w-[200px]，窄轨道里会溢出：min-w-0 + w-full 让字段贴合网格轨道 -->
          <div class="mt-3 grid grid-cols-2 gap-4 md:grid-cols-4">
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">区域 MinX</span>
              <YdNumberField v-model="updateForm.regionMinX" :step="1" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">区域 MinZ</span>
              <YdNumberField v-model="updateForm.regionMinZ" :step="1" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">区域 MaxX</span>
              <YdNumberField v-model="updateForm.regionMaxX" :step="1" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">区域 MaxZ</span>
              <YdNumberField v-model="updateForm.regionMaxZ" :step="1" class="w-full min-w-0" />
            </label>
          </div>
          <YdButton class="mt-2" size="sm" variant="outline" @click="clearRegion">
            清除范围（渲染整张图）
          </YdButton>
          <p v-if="preview && preview.meta" class="mt-2 text-xs text-muted-foreground">
            底图信息：原点 ({{ preview.meta.originX }}, {{ preview.meta.originZ }})，步长
            {{ preview.meta.step }}，尺寸 {{ preview.meta.width }}×{{ preview.meta.depth }}，
            表面高度区间 {{ preview.meta.minSurfaceY ?? '?' }} ~ {{ preview.meta.maxSurfaceY ?? '?' }}，
            建议 minY = {{ preview.suggestedMinY ?? '?' }}
          </p>
        </CollapsibleSection>

        <div class="mt-4 flex flex-wrap items-center justify-between gap-2 border-t pt-4">
          <span class="text-xs text-muted-foreground">修改自动保存，触发渲染时生效</span>
          <span v-if="savingParams" class="text-xs text-muted-foreground">保存中…</span>
          <span
            v-else-if="autoSaveLastResult === 'failed'"
            class="text-xs text-destructive"
          >自动保存失败，将在下次修改或触发渲染时重试</span>
          <span
            v-else-if="autoSaveLastResult === 'saved'"
            class="text-xs text-muted-foreground"
          >已自动保存：{{ autoSaveDetail }} · {{ autoSaveAt }}</span>
        </div>
      </YdCard>

      <!-- 第 2 步：渲染 -->
      <YdCard class="mb-4 p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h3 class="text-base font-semibold">② 渲染</h3>
          <div class="flex flex-wrap items-center gap-2">
            <YdButton
              size="sm"
              :disabled="isBusy(map.state) || Boolean(taskStream.activeTask.value) || packInspect.loading.value"
              @click="triggerRender"
            >
              <YdIcon name="i-mdi:play" />
              触发渲染
            </YdButton>
            <span v-if="packInspect.loading.value" class="text-xs text-muted-foreground">
              模组包解析中，解析完成后才能触发渲染（避免用未确认的模组组合开始渲染）
            </span>
            <YdButton
              v-if="taskStream.activeTask.value"
              size="sm"
              variant="destructive"
              @click="cancelActiveTask"
            >
              <YdIcon name="i-mdi:stop" />
              取消渲染
            </YdButton>
          </div>
        </div>
        <template v-if="taskStream.activeTask.value">
          <div class="mb-1 flex items-center gap-2 text-sm">
            <span>{{ renderPhaseLabel(taskStream.activeTask.value.phase) }}</span>
            <span class="min-w-0 flex-1 truncate text-muted-foreground" :title="taskStream.activeTask.value.message || ''">
              {{ taskStream.activeTask.value.message }}
            </span>
          </div>
          <YdProgress :model-value="taskStream.progressOf(taskStream.activeTask.value)" />
          <div class="mt-1 text-xs text-muted-foreground">
            {{ taskStream.progressOf(taskStream.activeTask.value) }}%{{ tileText(taskStream.activeTask.value) }}
          </div>
          <CollapsibleSection
            v-if="taskStream.activeTask.value.diagnostics?.length"
            title="渲染诊断"
            class="mt-2"
            default-open
          >
            <ul class="grid gap-0.5 font-mono text-xs leading-5">
              <li
                v-for="(line, index) in taskStream.activeTask.value.diagnostics ?? []"
                :key="index"
                class="break-words"
              >{{ line }}</li>
            </ul>
          </CollapsibleSection>
          <CollapsibleSection title="日志尾部" class="mt-2">
            <pre class="voxelith-log-pre max-h-48 rounded-md border border-border bg-muted/40 p-2 font-mono text-xs">{{ taskStream.activeTask.value.logTail }}</pre>
          </CollapsibleSection>
        </template>
        <div v-else class="text-sm text-muted-foreground">
          当前没有进行中的渲染任务
        </div>
      </YdCard>

      <!-- 第 3 步：查看成果（标注编辑已移至地图查看页） -->
      <YdCard class="mb-4 p-4">
        <h3 class="mb-2 text-base font-semibold">③ 查看成果</h3>
        <template v-if="isReady(map.state)">
          <canvas v-if="preview3dEnabled" ref="viewerContainer" class="voxelith-detail-canvas" />
          <div v-else class="flex flex-col items-center gap-2 py-8">
            <span class="text-sm text-muted-foreground">
              三维预览会持续占用显卡并按需拉取整图瓦片，需要时再加载
            </span>
            <YdButton size="sm" @click="loadPreview3d">
              <YdIcon name="i-mdi:cube-outline" />
              加载三维预览
            </YdButton>
          </div>
        </template>
        <div v-else class="py-8 text-center text-sm text-muted-foreground">
          该地图尚未就绪，无法预览
        </div>
        <div v-if="viewerError" class="mt-2 text-sm text-destructive">{{ viewerError }}</div>
      </YdCard>

      <!-- 扩展能力：全景图 -->
      <YdCard class="mb-4 p-4">
        <CollapsibleSection title="全景图烘焙" :subtitle="panoramaStateMeta.label" storage-key="panorama">
          <div class="mb-3 flex flex-wrap items-center gap-2">
            <YdTag :variant="panoramaStateMeta.variant">
              {{ panoramaLoading && !panorama ? '检测中…' : panoramaStateMeta.label }}
            </YdTag>
            <YdButton
              size="sm"
              :loading="panoramaRunning"
              :disabled="!isReady(map.state) || panoramaRunning"
              @click="triggerPanorama"
            >
              {{ panorama?.generated ? '重新烘焙' : '烘焙全景图' }}
            </YdButton>
          </div>
          <p class="mb-3 text-xs text-muted-foreground">
            全景图把整张地图烘成一张透视 PNG，客户端不必同时画出几千万三角形，适合当门面与分享封面。
            它读取渲染留下的中间产物（地表栅格 + 高度场），因此必须先成功渲染过一次；
            若提示缺少 surface.png，重新渲染一次即可。
          </p>
          <div class="grid gap-4 md:grid-cols-3">
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">输出宽度 (px)</span>
              <YdNumberField v-model="panoramaForm.width" :min="256" :max="8192" :step="256" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">输出高度 (px)</span>
              <YdNumberField v-model="panoramaForm.height" :min="256" :max="8192" :step="256" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">水平朝向 yaw（度）</span>
              <YdNumberField v-model="panoramaForm.yaw" :min="-360" :max="360" :step="5" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">俯角 pitch（度）</span>
              <YdNumberField v-model="panoramaForm.pitch" :min="-90" :max="90" :step="5" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">视场角 fov（度）</span>
              <YdNumberField v-model="panoramaForm.fov" :min="10" :max="120" :step="5" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">相机距离系数</span>
              <YdNumberField v-model="panoramaForm.distance" :min="0.1" :max="6" :step="0.05" class="w-full min-w-0" />
            </label>
          </div>

          <div v-if="panoramaRunning" class="mt-3">
            <YdProgress :model-value="panorama?.percent ?? 0" />
            <div class="mt-1 text-xs text-muted-foreground">
              {{ panorama?.percent ?? 0 }}% · {{ panorama?.message }}
            </div>
          </div>
          <div v-else-if="panorama?.state === 'FAILED'" class="mt-3 min-w-0 text-sm text-destructive">
            烘焙失败：<span class="break-all">{{ panorama?.error }}</span>
          </div>
          <div v-else-if="panorama?.message" class="mt-3 text-xs text-muted-foreground">
            {{ panorama?.message }}（{{ formatTime(panorama?.finishedAt) }}）
          </div>
          <div v-if="panoramaError" class="mt-2 text-sm text-destructive">{{ panoramaError }}</div>

          <div v-if="panoramaUrl" class="mt-3">
            <img :src="panoramaUrl" class="voxelith-panorama-img" alt="地图全景图">
            <p class="mt-1 text-xs text-muted-foreground">
              当前已发布代的 panorama.png；重新烘焙成功后会自动刷新缓存。
            </p>
          </div>
          <pre
            v-if="panorama?.logTail"
            class="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-2 font-mono text-xs"
          >{{ panorama.logTail }}</pre>
        </CollapsibleSection>
      </YdCard>

      <!-- 扩展能力：分片 worker -->
      <YdCard class="mb-4 p-4">
        <CollapsibleSection title="分布式分片 worker" :subtitle="shardStateMeta.label" storage-key="shard-worker">
          <div class="mb-3 flex flex-wrap items-center gap-2">
            <YdTag :variant="shardStateMeta.variant">
              {{ shardLoading && !shard ? '检测中…' : shardStateMeta.label }}
            </YdTag>
            <YdButton
              v-if="!shardAlive"
              size="sm"
              :loading="shardAction"
              :disabled="!map.activeGenerationId"
              @click="startShard"
            >
              <YdIcon name="i-mdi:play-circle-outline" />
              启动 worker
            </YdButton>
            <YdButton
              v-else
              size="sm"
              variant="destructive"
              :loading="shardAction"
              @click="stopShard"
            >
              <YdIcon name="i-mdi:stop-circle-outline" />
              停止 worker
            </YdButton>
          </div>
          <p class="mb-3 text-xs text-muted-foreground">{{ shard?.queueHint || WORKER_QUEUE_HINT }}</p>

          <div class="grid gap-4 md:grid-cols-3">
            <label class="grid gap-1.5">
              <span class="text-sm">worker 标识（留空自动生成）</span>
              <YdInput v-model="shardForm.workerId" placeholder="自动" :disabled="shardAlive" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">本地线程数</span>
              <YdNumberField v-model="shardForm.threads" :min="1" :max="32" :step="1" :disabled="shardAlive" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">租约时长（分钟）</span>
              <YdNumberField v-model="shardForm.leaseMinutes" :min="1" :max="1440" :step="5" :disabled="shardAlive" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">空闲退出（分钟）</span>
              <YdNumberField v-model="shardForm.idleMinutes" :min="1" :max="1440" :step="1" :disabled="shardAlive" class="w-full min-w-0" />
            </label>
          </div>

          <div v-if="shard" class="mt-3 grid gap-1.5 text-sm">
            <div class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">进程 pid：</span>
              <span>{{ shard.pid > 0 ? shard.pid : '—' }}</span>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">worker 标识：</span>
              <span class="break-all">{{ shard.workerId || '—' }}</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="shrink-0 text-muted-foreground">队列目录：</span>
              <code class="min-w-0 flex-1 break-all">{{ shard.queueDir }}</code>
              <YdButton size="sm" variant="ghost" @click="copyDir(shard.queueDir, '队列目录')">
                <YdIcon name="i-mdi:content-copy" />
              </YdButton>
            </div>
            <div class="flex items-center gap-2">
              <span class="shrink-0 text-muted-foreground">产物目录：</span>
              <code class="min-w-0 flex-1 break-all">{{ shard.artifactDir }}</code>
              <YdButton size="sm" variant="ghost" @click="copyDir(shard.artifactDir, '产物目录')">
                <YdIcon name="i-mdi:content-copy" />
              </YdButton>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">发布到：</span>
              <span class="break-all">{{ shard.publishedTo || '—' }}</span>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">启动于：</span>
              <span>{{ formatTime(shard.startedAt) }}</span>
            </div>
            <div v-if="shard.syncedTiles > 0" class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">已回传产物：</span>
              <span>{{ shard.syncedTiles }} 个</span>
            </div>
            <div v-if="shard.exitNote" class="flex gap-2">
              <span class="shrink-0 text-muted-foreground">退出说明：</span>
              <span class="break-all">{{ shard.exitNote }}</span>
            </div>
            <div v-if="shard.error" class="break-all text-destructive">错误：{{ shard.error }}</div>
          </div>

          <div v-if="shardNote" class="mt-2 text-xs text-muted-foreground">{{ shardNote }}</div>
          <div v-if="shardError" class="mt-2 text-sm text-destructive">{{ shardError }}</div>
          <pre
            v-if="shard?.logTail"
            class="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-2 font-mono text-xs"
          >{{ shard.logTail }}</pre>
        </CollapsibleSection>
      </YdCard>

      <!-- 任务历史：折叠收敛，表格很长时不再把全景图/worker 等区块顶出视野 -->
      <CollapsibleSection
        title="任务历史"
        :subtitle="taskStream.tasks.value.length ? `共 ${taskStream.tasks.value.length} 条` : '暂无'"
        storage-key="task-history"
        class="mb-4"
      >
        <YdResponsiveTable
          row-key="id"
          border
          stripe
          :columns="taskColumns"
          :data="taskStream.tasks.value"
        >
        <template #cell-id="{ row }">
          <span class="font-mono text-xs" :title="row.original.id">{{ shortId(row.original.id) }}</span>
        </template>
        <template #cell-state="{ row }">
          <YdTag :variant="TASK_STATE_META[row.original.state as RenderTaskState]?.variant || 'secondary'">
            {{ TASK_STATE_META[row.original.state as RenderTaskState]?.label || row.original.state }}
          </YdTag>
        </template>
        <template #cell-phase="{ row }">
          <span class="text-sm text-muted-foreground">{{ renderPhaseLabel(row.original.phase) }}</span>
        </template>
        <template #cell-progress="{ row }">
          <div v-if="row.original.state === 'RUNNING'" class="grid min-w-0 gap-1">
            <YdProgress :model-value="taskStream.progressOf(row.original)" />
            <span class="text-xs text-muted-foreground">
              {{ taskStream.progressOf(row.original) }}%{{ tileText(row.original) }}
            </span>
          </div>
          <div v-else-if="row.original.state === 'FAILED' || row.original.state === 'CANCELED'" class="flex min-w-0 items-center gap-1.5 text-xs text-destructive">
            <span class="min-w-0 max-w-[180px] flex-1 truncate" :title="taskError(row.original)">{{ taskError(row.original) }}</span>
            <YdButton size="sm" variant="ghost" title="查看失败详情" @click="openErrorDetail(row.original)">
              <YdIcon name="i-mdi:text-box-search-outline" />
            </YdButton>
          </div>
          <div v-else class="flex min-w-0 items-center gap-1.5 text-sm text-muted-foreground">
            <span class="shrink-0">{{ taskStream.progressOf(row.original) }}%{{ tileText(row.original) }}</span>
            <YdButton
              v-if="row.original.logTail"
              size="sm"
              variant="ghost"
              title="查看任务日志"
              @click="openErrorDetail(row.original)"
            >
              <YdIcon name="i-mdi:script-text-outline" />
            </YdButton>
          </div>
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-finishedAt="{ row }">{{ formatTime(row.original.finishedAt) }}</template>
        <template #empty>
          <span class="text-muted-foreground">暂无渲染任务</span>
        </template>
        <template #card="{ row }">
          <YdCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words font-mono text-sm">{{ shortId(row.id) }}</span>
                <YdTag :variant="TASK_STATE_META[row.state as RenderTaskState]?.variant || 'secondary'">
                  {{ TASK_STATE_META[row.state as RenderTaskState]?.label || row.state }}
                </YdTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">阶段</span><span class="break-all">{{ renderPhaseLabel(row.phase) }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">进度</span><span class="break-all">{{ taskStream.progressOf(row) }}%{{ tileText(row) }}</span></div>
                <div
                  v-if="row.logTail"
                  class="flex items-center gap-2"
                >
                  <span class="shrink-0 text-secondary-foreground/60">日志</span>
                  <YdButton size="sm" variant="ghost" @click="openErrorDetail(row)">
                    查看
                  </YdButton>
                </div>
                <div v-if="row.state === 'FAILED' || row.state === 'CANCELED'" class="flex min-w-0 gap-2 text-destructive">
                  <span class="shrink-0">错误</span>
                  <span class="min-w-0 flex-1 truncate" :title="taskError(row)">{{ taskError(row) }}</span>
                  <YdButton size="sm" variant="ghost" title="查看失败详情" @click="openErrorDetail(row)">
                    <YdIcon name="i-mdi:text-box-search-outline" />
                  </YdButton>
                </div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">创建</span><span>{{ formatTime(row.createdAt) }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">完成</span><span>{{ formatTime(row.finishedAt) }}</span></div>
              </div>
            </div>
          </YdCard>
        </template>
      </YdResponsiveTable>
      </CollapsibleSection>

      <YdDrawer
        v-model="errorDrawerOpen"
        :title="errorDrawerTitle"
        side="right"
        :show-confirm-button="false"
        :footer="false"
        content-class="voxelith-log-drawer"
      >
        <div class="grid min-w-0 gap-3">
          <div
            v-if="selectedTaskError?.diagnostics?.length"
            class="min-w-0 rounded-md border border-border bg-muted/40 p-3"
          >
            <div class="mb-1 text-xs font-medium text-muted-foreground">渲染诊断</div>
            <ul class="grid gap-0.5 font-mono text-xs leading-5">
              <li
                v-for="(line, index) in selectedTaskError?.diagnostics ?? []"
                :key="index"
                class="break-words"
              >{{ line }}</li>
            </ul>
          </div>
          <template v-if="selectedTaskError">
            <div>
              <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
                <div class="text-xs font-medium text-muted-foreground">任务日志（尾部，最近 4000 字）</div>
                <YdButton
                  size="sm"
                  variant="outline"
                  :loading="taskLogDownloading === selectedTaskError.id"
                  @click="downloadTaskLogFile(selectedTaskError)"
                >
                  下载完整日志
                </YdButton>
              </div>
              <pre
                class="voxelith-log-pre max-h-[55vh] rounded-md border border-border bg-muted/40 p-3 font-mono text-xs leading-5"
              >{{ selectedTaskError.logTail || '（无日志）' }}</pre>
            </div>
            <div v-if="selectedTaskError.state === 'FAILED' || selectedTaskError.state === 'CANCELED'">
              <div class="mb-1 text-xs font-medium text-destructive">失败原因</div>
              <pre
                class="voxelith-log-pre max-h-[30vh] rounded-md border border-destructive/30 bg-muted/40 p-3 font-mono text-xs leading-5"
              >{{ taskError(selectedTaskError) }}</pre>
            </div>
          </template>
          <template v-else>
            <pre
              class="voxelith-log-pre max-h-[70vh] rounded-md border border-border bg-muted/40 p-3 font-mono text-xs leading-5"
            >{{ map?.lastError }}</pre>
          </template>
        </div>
      </YdDrawer>
    </template>
  </YdPageMain>
</template>
