import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createVoxelithMapAdminApi } from '../api/voxelith-map-admin-api'
import type { CreateMapPayload, MapAdmin, MapAdminState, MapRenderSettings, PanoramaState, RenderTask, RenderTaskState, ShardWorkerState } from '../types'
import { renderTaskProgress } from './useRenderTaskProgress'
import { consumeSseStream } from './sse-fetch'
import { DIMENSION_OPTIONS, dimensionLabel } from './dimensionLabels'

// 维度文案是纯数据，定义在 dimensionLabels（渲染页/展示页/单测都要用，不该被本模块的宿主依赖拖住）
export { DIMENSION_OPTIONS, dimensionLabel }

const POLL_INTERVAL = 2500

const DEFAULT_SETTINGS: MapRenderSettings = {
  minY: -64,
  maxLevel: 0,
  lodAtlas: true,
  meshopt: true,
  maxChunks: 0,
  batchChunks: 2048,
  skipHarvest: false,
  // 留空 = 引擎自动判定（资源包文件名 → 存档版本 → 兜底常量）。
  // 写死曾导致：存档是 1.21.1 而采集按 1.20.1 跑，成片品红与空洞，用户完全看不出原因。
  mcVersion: '',
  loaderVersion: '0.16.14',
}

/** mcVersion 输入框下方的说明，创建与详情页共用一套口径 */
export const MC_VERSION_HINT = '留空 = 自动判定（优先取客户端 jar 文件名里的版本，其次存档版本）'

export const MAP_STATE_META: Record<MapAdminState, { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  EMPTY: { label: '未渲染', variant: 'secondary' },
  QUEUED: { label: '排队中', variant: 'secondary' },
  PREPARING: { label: '准备中', variant: 'default' },
  RENDERING: { label: '渲染中', variant: 'default' },
  READY: { label: '已就绪', variant: 'outline' },
  CANCELED: { label: '已取消', variant: 'secondary' },
  FAILED: { label: '失败', variant: 'destructive' },
  DELETING: { label: '删除中', variant: 'secondary' },
  DELETED: { label: '已删除', variant: 'secondary' },
}

/** 渲染任务状态（注意与 MapAdminState 不同，任务态包含 PENDING/RUNNING/SUCCEEDED） */
export const TASK_STATE_META: Record<RenderTaskState, { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  PENDING: { label: '排队中', variant: 'secondary' },
  RUNNING: { label: '进行中', variant: 'default' },
  SUCCEEDED: { label: '成功', variant: 'outline' },
  CANCELED: { label: '已取消', variant: 'secondary' },
  FAILED: { label: '失败', variant: 'destructive' },
}

/** 全景图烘焙状态（后端 PanoramaService 的字面量） */
export const PANORAMA_STATE_META: Record<PanoramaState, { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  IDLE: { label: '未烘焙', variant: 'secondary' },
  RUNNING: { label: '烘焙中', variant: 'default' },
  SUCCEEDED: { label: '已生成', variant: 'outline' },
  FAILED: { label: '烘焙失败', variant: 'destructive' },
}

/**
 * 分片 worker 状态。EXITED 是「进程自行结束」——空闲超时退出属正常，故用中性样式，
 * 真正的错误看卡片里的 error / exitNote。
 */
export const SHARD_WORKER_STATE_META: Record<ShardWorkerState, { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  STOPPED: { label: '未启动', variant: 'secondary' },
  RUNNING: { label: '运行中', variant: 'default' },
  EXITED: { label: '已退出', variant: 'outline' },
}

export const RENDER_PHASE_LABEL: Record<string, string> = {
  QUEUED: '排队',
  PREPARE: '准备',
  IMPORT: '导入存档',
  EXTRACT: '解压存档',
  ASSETS: '加载资源',
  HIRES: '生成高精瓦片',
  LOD: '生成概览瓦片',
  PUBLISH: '发布地图',
}

export function renderPhaseLabel(phase: string | null | undefined): string {
  if (!phase) return '—'
  return RENDER_PHASE_LABEL[phase] ?? phase
}

export function formatTime(ts?: string | null): string {
  if (ts === undefined || ts === null || ts === '') return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatError(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** 管理端地图列表：加载、2.5s 轮询（有渲染中地图或活跃任务时）、SSE 实时任务、新建、渲染、删除 */
export function useVoxelithAdmin(sdk: YuDreamPluginSdk) {
  const api = createVoxelithMapAdminApi(sdk)
  const toast = useYdToast()

  const maps = ref<MapAdmin[]>([])
  const tasks = ref<RenderTask[]>([])
  const loading = ref(false)
  const operating = ref('')

  const createOpen = ref(false)
  const createForm = reactive<{
    name: string
    worldName: string
    dimension: string
    minY: number
    /** 一次建出存档包内的全部维度（world / world_nether / world_the_end）；默认开 */
    allDimensions: boolean
  }>({
    name: '',
    worldName: 'world',
    dimension: 'minecraft:overworld',
    minY: -64,
    allDimensions: true,
  })
  const worldFile = ref<File | null>(null)
  const clientJarFile = ref<File | null>(null)
  /** 从客户端 jar 库预选的版本（与手动上传二选一；库条目优先级低，选了文件就走上传） */
  const clientJarPreset = ref<{ fileId: string, name: string } | null>(null)
  /**
   * 选中 zip 时立即上传好的模组包（为了立刻解析包内 jar，见 useModPackInspect）。
   *
   * <p>提交时直接复用这份，不再把 GB 级包传第二遍；用户在列表里移除该文件后即失效。</p>
   */
  const modsPackPreset = ref<{ fileId: string, name: string } | null>(null)
  /** 模组 jar（可多选；创建时逐个上传，按选择顺序叠加在 client jar 之上） */
  const modJarFiles = ref<File[]>([])
  const creating = ref(false)
  const createStep = ref('')

  const createValid = computed(() => createForm.name.trim().length > 0 && worldFile.value !== null)
  const activeTasksByMapId = computed(() => {
    const byMap = new Map<string, RenderTask>()
    for (const task of tasks.value) {
      if (task.state !== 'PENDING' && task.state !== 'RUNNING') continue
      const existing = byMap.get(task.mapId)
      const created = task.createdAt ? Date.parse(task.createdAt) : 0
      const existingCreated = existing?.createdAt ? Date.parse(existing.createdAt) : 0
      if (!existing || created > existingCreated) {
        byMap.set(task.mapId, task)
      }
    }
    return byMap
  })

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let sseAbort: AbortController | null = null
  let mounted = true

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function schedulePolling(): void {
    stopPolling()
    const active = maps.value.some(m => m.state === 'RENDERING' || m.state === 'PREPARING')
      || tasks.value.some(t => t.state === 'PENDING' || t.state === 'RUNNING')
    if (active) {
      pollTimer = setTimeout(() => void pollTasks(), POLL_INTERVAL)
    }
  }

  function isActiveTask(task: RenderTask): boolean {
    return task.state === 'PENDING' || task.state === 'RUNNING'
  }

  /**
   * 轮询只取任务，不再每次重新拉取整张地图列表。
   *
   * <p>地图列表要为每张地图读一次项目文档（对象存储），而这一轮询在有任务时每 2.5 秒跑一次：
   * 地图一多，光轮询就能把对象存储连接池占满。任务状态是内存态、不碰存储，且渲染进度还有 SSE
   * 推送；地图列表只在任务结束时刷新一次——那才是地图状态、封面、更新时间真正会变的时候。</p>
   */
  async function pollTasks(): Promise<void> {
    try {
      const res = await api.tasks()
      const next = res.tasks ?? []
      // 有任务从「进行中」变为不在进行中：地图状态/封面/updatedAt 可能已变，刷新一次列表
      const finished = tasks.value.some(
        previous => isActiveTask(previous) && !next.some(current => current.id === previous.id && isActiveTask(current)),
      )
      tasks.value = next
      if (finished) {
        await loadMaps(true)
        return
      }
    }
    catch {
      // 轮询失败静默：下一轮再试，不要用 toast 打扰
    }
    schedulePolling()
  }

  function upsertTask(task: RenderTask): void {
    const idx = tasks.value.findIndex(t => t.id === task.id)
    if (idx < 0) {
      tasks.value = [task, ...tasks.value]
    }
    else {
      tasks.value = tasks.value.map(t => t.id === task.id ? task : t)
    }
  }

  function connectTaskEvents(): void {
    if (typeof fetch === 'undefined' || sseAbort) return
    const controller = new AbortController()
    sseAbort = controller
    // EventSource 带不上 Authorization 头（宿主 sa-token 只认 header），会被 400；
    // 按后端 VoxelithEventStream 约定改用 fetch 流式读取（见 ./sse-fetch）。
    void consumeSseStream(api.taskEventsUrl(), controller.signal, (event, data) => {
      if (event !== 'task') return
      try {
        const task = JSON.parse(data) as RenderTask
        upsertTask(task)
        if (task.state === 'SUCCEEDED' || task.state === 'FAILED' || task.state === 'CANCELED') {
          void loadMaps(true)
        }
      }
      catch {
        // 畸形事件忽略，保留轮询兜底
      }
    })
      .catch(() => {
        // 连接失败/中断（未登录、宿主重启等）：交回轮询兜底，不自动重连以免刷屏
      })
      .finally(() => {
        if (sseAbort === controller)
          sseAbort = null
        if (mounted)
          schedulePolling()
      })
  }

  async function loadMaps(silent = false): Promise<void> {
    if (!silent) loading.value = true
    try {
      const [mapsRes, tasksRes] = await Promise.all([api.maps(), api.tasks()])
      maps.value = mapsRes.maps ?? []
      tasks.value = tasksRes.tasks ?? []
    }
    catch (e) {
      if (!silent) toast.error(formatError(e, '地图列表加载失败'))
    }
    finally {
      if (!silent) loading.value = false
      schedulePolling()
    }
  }

  function openCreate(): void {
    createForm.name = ''
    createForm.worldName = 'world'
    createForm.dimension = 'minecraft:overworld'
    createForm.minY = -64
    worldFile.value = null
    clientJarFile.value = null
    clientJarPreset.value = null
    modsPackPreset.value = null
    modJarFiles.value = []
    createStep.value = ''
    createOpen.value = true
  }

  async function uploadFile(file: File): Promise<string> {
    const uploaded = await sdk.files.uploadImage(file, { module: 'voxelith-map', publicAccess: false })
    if (!uploaded.id) {
      throw new Error(`文件 ${file.name} 上传后未返回 id`)
    }
    return uploaded.id
  }

  async function submitCreate(): Promise<void> {
    if (!createValid.value || creating.value) return
    creating.value = true
    try {
      createStep.value = `正在上传存档 ${worldFile.value!.name} …`
      const worldFileId = await uploadFile(worldFile.value!)
      let clientJarFileId: string | undefined
      let clientJarName = ''
      if (clientJarFile.value) {
        createStep.value = `正在上传客户端 ${clientJarFile.value.name} …`
        clientJarFileId = await uploadFile(clientJarFile.value)
        // 宿主不保留原名，而引擎要靠文件名判版本，所以在创建请求里一起带上
        clientJarName = clientJarFile.value.name
      }
      else if (clientJarPreset.value) {
        // 从 jar 库选的版本：文件已预上传，直接引用
        clientJarFileId = clientJarPreset.value.fileId
        clientJarName = clientJarPreset.value.name
      }
      // 模组：单个 jar 逐个上传（保持选择顺序，后端按顺序叠在 client jar 之上）；
      // mods 目录整包 zip 只登记成一个「模组包」引用，渲染前由后端解包——
      // 导入时就解成一个个 jar 会打出成百上千次写请求，把对象存储连接池占满
      const modJars: { fileId: string, name: string }[] = []
      let modsPackFileId = ''
      let modsPackName = ''
      for (const file of modJarFiles.value) {
        if (file.name.toLowerCase().endsWith('.zip')) {
          // 选中时已上传（为了立即解析）：直接复用，避免同一个包传两遍
          if (modsPackPreset.value && modsPackPreset.value.name === file.name) {
            modsPackFileId = modsPackPreset.value.fileId
            modsPackName = file.name
            continue
          }
          createStep.value = `正在上传模组包 ${file.name} …`
          modsPackFileId = await uploadFile(file)
          modsPackName = file.name
          continue
        }
        createStep.value = `正在上传模组 ${file.name} …`
        const fileId = await uploadFile(file)
        modJars.push({ fileId, name: file.name })
      }
      createStep.value = '正在创建地图…'
      const payload: CreateMapPayload = {
        name: createForm.name.trim(),
        // 地图名与存档名合并：存档名跟随名称（不再单独问一次）
        worldName: createForm.name.trim() || 'world',
        dimension: createForm.dimension,
        // 包内并列维度一次建全（共享存档与分组）；关闭时只建 dimension 指定的那一张
        allDimensions: createForm.allDimensions,
        worldFileId,
        clientJarFileId: clientJarFileId ?? '',
        clientJarName,
        modsPackFileId,
        modsPackName,
        modJars,
        settings: { ...DEFAULT_SETTINGS, minY: createForm.minY },
      }
      await api.createMap(payload)
      toast.success('地图已创建')
      createOpen.value = false
      await loadMaps(true)
    }
    catch (e) {
      toast.error(formatError(e, '地图创建失败'))
    }
    finally {
      creating.value = false
      createStep.value = ''
    }
  }

  async function triggerRender(map: MapAdmin): Promise<void> {
    operating.value = map.id
    try {
      await api.render(map.id)
      toast.success(`已触发「${map.name}」渲染`)
      await loadMaps(true)
    }
    catch (e) {
      toast.error(formatError(e, '触发渲染失败'))
    }
    finally {
      operating.value = ''
    }
  }

  async function removeMap(map: MapAdmin): Promise<void> {
    operating.value = map.id
    try {
      await api.startDeleteJob(map.id)
      toast.success(`「${map.name}」已删除`)
      await loadMaps(true)
    }
    catch (e) {
      toast.error(formatError(e, '删除失败'))
    }
    finally {
      operating.value = ''
    }
  }

  onMounted(() => {
    connectTaskEvents()
    void loadMaps()
  })
  onBeforeUnmount(() => {
    mounted = false
    stopPolling()
    sseAbort?.abort()
    sseAbort = null
  })

  return {
    maps,
    tasks,
    activeTasksByMapId,
    loading,
    operating,
    createOpen,
    createForm,
    worldFile,
    clientJarFile,
    clientJarPreset,
    modsPackPreset,
    modJarFiles,
    creating,
    createStep,
    createValid,
    loadMaps,
    openCreate,
    submitCreate,
    triggerRender,
    removeMap,
    renderTaskProgress,
    // 实例导入等流程要把下载到的整包 zip 交给同一个宿主上传通道（multipart 二进制）
    uploadFile,
  }
}
