import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createWorldMapAdminApi } from '../api/world-map-admin-api'
import type { MapAdmin, MapAdminState, MapDimension, RenderPhase, RenderTask } from '../types'

const POLL_INTERVAL = 2500

export const DIMENSION_OPTIONS: { label: string, value: MapDimension }[] = [
  { label: '主世界', value: 'overworld' },
  { label: '下界', value: 'nether' },
  { label: '末地', value: 'the_end' },
]

export function dimensionLabel(dimension: string): string {
  return DIMENSION_OPTIONS.find(d => d.value === dimension)?.label ?? dimension
}

export const MAP_STATE_META: Record<MapAdminState, { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  EMPTY: { label: '未渲染', variant: 'secondary' },
  RENDERING: { label: '渲染中', variant: 'default' },
  READY: { label: '已就绪', variant: 'outline' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
  FAILED: { label: '失败', variant: 'destructive' },
}

export const TASK_STATE_META: Record<RenderTask['state'], { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  PENDING: { label: '排队中', variant: 'secondary' },
  RUNNING: { label: '运行中', variant: 'default' },
  SUCCESS: { label: '成功', variant: 'outline' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
  FAILED: { label: '失败', variant: 'destructive' },
}

export const RENDER_PHASE_LABEL: Record<RenderPhase, string> = {
  IMPORT: '准备输入',
  EXTRACT: '解压存档',
  ASSETS: '加载资源',
  HIRES: '生成高精瓦片',
  LOWRES: '生成概览瓦片',
  PUBLISH: '发布地图',
}

export function formatTime(ts?: number | string): string {
  if (!ts) {
    return '—'
  }
  // 宿主统一把长整型序列化为字符串，这里归一为数字再构造日期
  const d = new Date(Number(ts))
  if (Number.isNaN(d.getTime())) {
    return '—'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatError(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** 管理端地图列表：加载、2.5s 条件轮询（有 RENDERING 时）、新建（含文件上传）、渲染、删除 */
export function useWorldMapAdmin(sdk: YuDreamPluginSdk) {
  const api = createWorldMapAdminApi(sdk)
  const toast = useFaToast()

  const maps = ref<MapAdmin[]>([])
  const loading = ref(false)
  const operating = ref('')

  const createOpen = ref(false)
  const createForm = reactive<{ name: string, dimension: MapDimension, stripNetherCeiling: boolean }>({
    name: '',
    dimension: 'overworld',
    stripNetherCeiling: true,
  })
  const worldFile = ref<File | null>(null)
  const clientJarFile = ref<File | null>(null)
  const creating = ref(false)
  /** 上传/创建进度状态描述（sdk.files 无进度回调，展示阶段状态） */
  const createStep = ref('')

  const createValid = computed(() => createForm.name.trim().length > 0 && worldFile.value !== null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function schedulePolling(): void {
    stopPolling()
    if (maps.value.some(m => m.state === 'RENDERING')) {
      pollTimer = setTimeout(() => void loadMaps(true), POLL_INTERVAL)
    }
  }

  async function loadMaps(silent = false): Promise<void> {
    if (!silent) {
      loading.value = true
    }
    try {
      const res = await api.maps()
      maps.value = res.maps ?? []
    }
    catch (e) {
      if (!silent) {
        toast.error(formatError(e, '地图列表加载失败'))
      }
    }
    finally {
      if (!silent) {
        loading.value = false
      }
      schedulePolling()
    }
  }

  function openCreate(): void {
    createForm.name = ''
    createForm.dimension = 'overworld'
    createForm.stripNetherCeiling = true
    worldFile.value = null
    clientJarFile.value = null
    createStep.value = ''
    createOpen.value = true
  }

  async function uploadFile(file: File): Promise<string> {
    const uploaded = await sdk.files.uploadImage(file, { module: 'world-map', publicAccess: false })
    if (!uploaded.id) {
      throw new Error(`文件 ${file.name} 上传后未返回 id`)
    }
    return uploaded.id
  }

  async function submitCreate(): Promise<void> {
    if (!createValid.value || creating.value) {
      return
    }
    creating.value = true
    try {
      createStep.value = `正在上传存档 ${worldFile.value!.name} …`
      const worldFileId = await uploadFile(worldFile.value!)
      let clientJarFileId: string | undefined
      if (clientJarFile.value) {
        createStep.value = `正在上传客户端 ${clientJarFile.value.name} …`
        clientJarFileId = await uploadFile(clientJarFile.value)
      }
      createStep.value = '正在创建地图…'
      await api.createMap({
        name: createForm.name.trim(),
        dimension: createForm.dimension,
        worldFileId,
        clientJarFileId,
        stripNetherCeiling: createForm.stripNetherCeiling,
      })
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
      await api.deleteMap(map.id)
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

  onMounted(() => void loadMaps())
  onBeforeUnmount(stopPolling)

  return {
    maps,
    loading,
    operating,
    createOpen,
    createForm,
    worldFile,
    clientJarFile,
    creating,
    createStep,
    createValid,
    loadMaps,
    openCreate,
    submitCreate,
    triggerRender,
    removeMap,
  }
}

/** 地图详情：地图信息 + 渲染任务历史（前端按 mapId 过滤），有活跃任务时 2.5s 轮询 */
export function useWorldMapMapDetail(sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded) {
  const api = createWorldMapAdminApi(sdk)
  const toast = useFaToast()

  const mapId = computed(() => String(route?.query?.id ?? ''))
  const map = ref<MapAdmin | null>(null)
  const tasks = ref<RenderTask[]>([])
  const loading = ref(false)
  const error = ref('')
  const cancellingTaskId = ref('')
  const activeTask = computed(() => tasks.value.find(task => task.state === 'PENDING' || task.state === 'RUNNING') ?? null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let taskEvents: EventSource | null = null

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function schedulePolling(): void {
    stopPolling()
    if (taskEvents) return
    const active = map.value?.state === 'RENDERING'
      || tasks.value.some(t => t.state === 'PENDING' || t.state === 'RUNNING')
    if (active) {
      pollTimer = setTimeout(() => void load(true), POLL_INTERVAL)
    }
  }

  async function load(silent = false): Promise<void> {
    if (!mapId.value) {
      error.value = '缺少地图 id'
      return
    }
    if (!silent) {
      loading.value = true
      error.value = ''
    }
    try {
      const [mapsRes, tasksRes] = await Promise.all([api.maps(), api.tasks()])
      map.value = (mapsRes.maps ?? []).find(m => m.id === mapId.value) ?? null
      if (!map.value) {
        error.value = '地图不存在或已被删除'
      }
      tasks.value = (tasksRes.tasks ?? [])
        .filter(t => t.mapId === mapId.value)
        .sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0))
    }
    catch (e) {
      if (!silent) {
        error.value = formatError(e, '地图详情加载失败')
      }
    }
    finally {
      if (!silent) {
        loading.value = false
      }
      schedulePolling()
    }
  }

  function connectTaskEvents(): void {
    if (typeof EventSource === 'undefined' || taskEvents || !mapId.value) return
    const source = new EventSource(api.taskEventsUrl())
    taskEvents = source
    source.addEventListener('task', event => {
      try {
        const task = JSON.parse((event as MessageEvent<string>).data) as RenderTask
        if (task.mapId !== mapId.value) return
        const index = tasks.value.findIndex(item => item.id === task.id)
        tasks.value = index < 0
          ? [task, ...tasks.value]
          : tasks.value.map(item => item.id === task.id ? task : item)
        if (task.state === 'SUCCESS' || task.state === 'FAILED' || task.state === 'CANCELLED') {
          void load(true)
        }
      }
      catch {
        // Ignore a malformed event and retain the polling fallback for a later refresh.
      }
    })
    source.addEventListener('error', () => {
      if (taskEvents !== source) return
      source.close()
      taskEvents = null
      schedulePolling()
    })
  }

  function disconnectTaskEvents(): void {
    taskEvents?.close()
    taskEvents = null
  }

  function taskProgress(task: RenderTask): number {
    if (typeof task.progressPercent === 'number') {
      return Math.min(100, Math.max(0, task.progressPercent))
    }
    if (!task.totalTiles) {
      return task.state === 'SUCCESS' ? 100 : 0
    }
    return Math.min(100, Math.round((task.doneTiles / task.totalTiles) * 100))
  }

  async function triggerRender(): Promise<void> {
    if (!map.value) {
      return
    }
    try {
      await api.render(map.value.id)
      toast.success('已触发渲染')
      await load(true)
    }
    catch (e) {
      toast.error(formatError(e, '触发渲染失败'))
    }
  }

  async function cancelActiveTask(): Promise<void> {
    const task = activeTask.value
    if (!task || cancellingTaskId.value) return
    cancellingTaskId.value = task.id
    try {
      await api.cancelTask(task.id)
      toast.success('已请求取消渲染任务')
      await load(true)
    }
    catch (e) {
      toast.error(formatError(e, '取消渲染任务失败'))
    }
    finally {
      cancellingTaskId.value = ''
    }
  }

  onMounted(() => {
    connectTaskEvents()
    void load()
  })
  onBeforeUnmount(() => {
    disconnectTaskEvents()
    stopPolling()
  })

  return {
    mapId,
    map,
    tasks,
    activeTask,
    loading,
    error,
    cancellingTaskId,
    load,
    taskProgress,
    triggerRender,
    cancelActiveTask,
  }
}
