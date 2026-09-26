import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { createMcPanelExtra } from '../api/api-extra.ts'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { sha256HexOfFile } from '../utils/sha256.ts'

/** 与后端 UploadTaskService 的任务视图对齐。 */
export interface UploadTaskView {
  taskId: string
  path: string
  name: string
  size: number
  uploaded: number
  state: 'running' | 'done' | 'failed' | 'canceled'
  error?: string
}

/** 浏览器分片大小（4MiB/片；面板会再拆 96KiB 帧转发节点）。 */
const UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024
const POLL_MS = 2_000

/**
 * 实例文件上传任务（异步 + 实时进度 + 跨页面恢复）：
 * - startUpload 立即返回（后台串行分片直传面板），页面操作不被阻塞；
 * - 本地任务对象驱动即时进度；轮询后端任务列表同步状态（并发上传/其他页面的任务）；
 * - 页面刷新后重进文件页/详情页，running 任务从后端恢复进度（发送端已断的任务
 *   会被后端空闲超时置为失败并显示原因）。
 */
export function useInstanceUploads(sdk: YuDreamPluginSdk, instanceId: Ref<string>) {
  const extra = createMcPanelExtra(sdk)
  const tasks = ref<UploadTaskView[]>([])
  const hashingIds = ref<Set<string>>(new Set())
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const hasRunning = computed(() => tasks.value.some(task => task.state === 'running'))

  async function refresh() {
    const id = instanceId.value
    if (!id) {
      return
    }
    try {
      const result = await extra.listUploadTasks(id) as { tasks?: UploadTaskView[] }
      if (id === instanceId.value && result?.tasks) {
        // 保留本地「校验中」占位任务（尚未 begin 成功，后端还没有记录）
        const localPending = tasks.value.filter(task => task.taskId.startsWith('local-'))
        tasks.value = [...localPending, ...result.tasks]
      }
    }
    catch {
      // 列表读取失败保持现状（下轮轮询重试）
    }
  }

  function startPolling() {
    if (pollTimer == null) {
      pollTimer = setInterval(() => {
        if (!document.hidden) {
          void refresh()
        }
      }, POLL_MS)
    }
  }

  function stopPolling() {
    if (pollTimer != null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  watch(hasRunning, (running) => {
    if (running) {
      startPolling()
    }
    else {
      // 无运行中任务后再同步一轮（拿最终态）并停止轮询
      void refresh()
      stopPolling()
    }
  }, { immediate: true })

  onBeforeUnmount(stopPolling)

  function patchLocal(taskId: string, patch: Partial<UploadTaskView>) {
    const index = tasks.value.findIndex(task => task.taskId === taskId)
    if (index >= 0) {
      tasks.value[index] = { ...tasks.value[index], ...patch }
    }
  }

  /**
   * 发起上传：立即入列（异步后台执行）。返回值不等待上传完成；
   * onSettled 在该任务到达终态时回调（成功/失败都调），供页面刷新列表。
   */
  function startUpload(file: File, path: string, onSettled?: (ok: boolean) => void): void {
    const id = instanceId.value
    if (!id) {
      return
    }
    const localId = `local-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    tasks.value.unshift({
      taskId: localId,
      path,
      name: file.name,
      size: file.size,
      uploaded: 0,
      state: 'running',
    })
    hashingIds.value = new Set([...hashingIds.value, localId])
    void (async () => {
      let currentId = localId
      try {
        // 1) 整文件 sha256（节点 commit 强校验；分块读不进全量内存）
        const sha256 = await sha256HexOfFile(file)
        hashingIds.value = new Set([...hashingIds.value].filter(value => value !== localId))
        // 2) 建任务并申请节点会话
        const begin = await extra.beginUploadTask(id, path, file.size, sha256, file.name) as UploadTaskView
        currentId = begin.taskId
        patchLocal(localId, { taskId: currentId })
        // 3) 串行分片直传面板（面板流式中转节点；顺序由循环天然保证）
        for (let offset = 0; offset < file.size; offset += UPLOAD_CHUNK_BYTES) {
          const blob = file.slice(offset, Math.min(offset + UPLOAD_CHUNK_BYTES, file.size))
          const result = await extra.uploadTaskChunk(id, currentId, offset, blob) as { uploaded?: number }
          patchLocal(currentId, { uploaded: Number(result?.uploaded ?? offset + blob.size) })
        }
        // 4) 提交：节点校验 size+sha256 后落盘
        await extra.commitUploadTask(id, currentId)
        patchLocal(currentId, { state: 'done', uploaded: file.size })
        onSettled?.(true)
      }
      catch (error) {
        hashingIds.value = new Set([...hashingIds.value].filter(value => value !== localId))
        // 失败详情以后端任务视图为准（轮询会覆盖）；本地先置失败占位给出即时反馈
        patchLocal(currentId, { state: 'failed', error: String((error as Error)?.message ?? '上传失败') })
        onSettled?.(false)
      }
    })()
  }

  async function cancelTask(taskId: string): Promise<void> {
    const id = instanceId.value
    if (!id || taskId.startsWith('local-')) {
      return
    }
    await extra.cancelUploadTask(id, taskId)
    void refresh()
  }

  function isHashing(taskId: string): boolean {
    return hashingIds.value.has(taskId)
  }

  return { tasks, hasRunning, startUpload, cancelTask, refresh, isHashing }
}
