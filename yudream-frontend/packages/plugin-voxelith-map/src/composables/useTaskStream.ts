import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import type { VoxelithMapAdminApi } from '../api/voxelith-map-admin-api'
import type { RenderTask } from '../types'
import { isTaskActive, renderTaskProgress } from './useRenderTaskProgress'
import { consumeSseStream } from './sse-fetch'

const POLL_INTERVAL = 2000

export interface TaskStreamOptions {
  /** 任务绑定到的地图 id（scoped = true 时用于过滤 SSE 与拉取专属任务列表） */
  mapId: Ref<string | undefined>
  /** true：拉取 /admin/maps/{id}/tasks 并按 mapId 过滤 SSE；false：拉取 /admin/tasks（全部） */
  scoped?: boolean
}

/**
 * 渲染任务流：fetch 流式 SSE 实时推送 + 2 秒轮询兜底。
 * EventSource 带不上 Authorization 头（宿主 sa-token 只认 header），直接用会被 400；
 * 故按后端 VoxelithEventStream 约定走 fetch 流（见 ./sse-fetch），轮询始终是主兜底，
 * 二者都会更新同一份 tasks。
 */
export function useTaskStream(api: VoxelithMapAdminApi, options: TaskStreamOptions) {
  const tasks = ref<RenderTask[]>([])
  const loading = ref(false)
  const error = ref('')

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
    const active = tasks.value.some(t => isTaskActive(t))
    if (active) {
      pollTimer = setTimeout(() => { void refresh(true) }, POLL_INTERVAL)
    }
  }

  function upsertTask(task: RenderTask): void {
    const idx = tasks.value.findIndex(t => t.id === task.id)
    if (idx < 0) {
      tasks.value = [task, ...tasks.value]
    }
    else {
      tasks.value = tasks.value.map(t => t.id === task.id ? task : t)
    }
    if (task.state === 'SUCCEEDED' || task.state === 'FAILED' || task.state === 'CANCELED') {
      void refresh(true)
    }
  }

  async function fetchTasks(): Promise<void> {
    // mapId 允许不传（scoped=false 的全量页）；可选链防止误用 undefined
    const id = options.mapId?.value
    const res = options.scoped && id
      ? await api.mapTasks(id)
      : await api.tasks()
    if (!mounted) return
    tasks.value = res.tasks ?? []
  }

  async function refresh(silent = false): Promise<void> {
    if (!silent) loading.value = true
    try {
      await fetchTasks()
    }
    catch (e) {
      if (!silent) {
        error.value = e instanceof Error && e.message ? e.message : '任务加载失败'
      }
    }
    finally {
      if (!silent) loading.value = false
      schedulePolling()
    }
  }

  function connectSse(): void {
    if (typeof fetch === 'undefined' || sseAbort) return
    const controller = new AbortController()
    sseAbort = controller
    void consumeSseStream(api.taskEventsUrl(), controller.signal, (event, data) => {
      if (event !== 'task') return
      try {
        const task = JSON.parse(data) as RenderTask
        if (options.scoped && options.mapId.value && task.mapId !== options.mapId.value) return
        upsertTask(task)
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

  const activeTask = computed(() =>
    tasks.value.find(t => isTaskActive(t)) ?? null,
  )

  function progressOf(task: RenderTask): number {
    return renderTaskProgress(task)
  }

  function start(): void {
    connectSse()
    void refresh()
  }

  function stop(): void {
    stopPolling()
    sseAbort?.abort()
    sseAbort = null
  }

  onBeforeUnmount(() => {
    mounted = false
    stop()
  })

  return {
    tasks,
    loading,
    error,
    activeTask,
    progressOf,
    refresh,
    start,
    stop,
  }
}
