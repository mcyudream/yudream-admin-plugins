import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { createMcPanelExtra } from '../api/api-extra.ts'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { sha256HexOfFile } from '../utils/sha256.ts'
import { uploadRelativePath } from '../utils/uploadPaths.ts'

export interface UploadTaskView {
  taskId: string
  path: string
  name: string
  size: number
  uploaded: number
  state: 'queued' | 'running' | 'done' | 'failed' | 'canceled'
  error?: string
}

const UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024

/** Two workers match the server limit; cancellation is checked after each await. */
export function useInstanceUploads(sdk: YuDreamPluginSdk, instanceId: Ref<string>) {
  const extra = createMcPanelExtra(sdk)
  const tasks = ref<UploadTaskView[]>([])
  const hashingIds = ref<Set<string>>(new Set())
  const own = new Map<string, UploadTaskView>()
  type Job = { id: string, localId: string, view: UploadTaskView, file: File, canceled: boolean, generation: number, settled: (ok: boolean) => void }
  const jobs = new Map<string, Job>()
  const queue: Job[] = []
  let active = 0
  let generation = 0
  let polling = false
  let disposed = false
  const hasRunning = computed(() => tasks.value.some(task => task.state === 'queued' || task.state === 'running'))

  function publish() {
    const remote = tasks.value.filter(task => !own.has(task.taskId) && !task.taskId.startsWith('local-'))
    tasks.value = [...own.values(), ...remote]
  }

  async function refresh() {
    const id = instanceId.value
    const version = generation
    if (!id || polling || disposed) return
    polling = true
    try {
      const result = await extra.listUploadTasks(id) as { tasks?: UploadTaskView[] }
      if (version === generation && !disposed) {
        tasks.value = [...own.values(), ...(result.tasks ?? []).filter(task => !own.has(task.taskId))]
      }
    }
    catch { /* Keep known progress until the next successful poll. */ }
    finally {
      polling = false
      if (version !== generation && !disposed) void refresh()
    }
  }

  const timer = setInterval(() => {
    if (!document.hidden && hasRunning.value) void refresh()
  }, 2000)

  function update(job: Job, patch: Partial<UploadTaskView>) {
    Object.assign(job.view, patch)
    if (job.generation === generation && job.id === instanceId.value && own.has(job.view.taskId) && !disposed) publish()
  }

  async function abort(job: Job) {
    if (!job.view.taskId.startsWith('local-')) await extra.cancelUploadTask(job.id, job.view.taskId)
  }

  async function run(job: Job) {
    let ok = false
    try {
      update(job, { state: 'running' })
      hashingIds.value = new Set([...hashingIds.value, job.localId])
      const sha256 = await sha256HexOfFile(job.file)
      hashingIds.value = new Set([...hashingIds.value].filter(id => id !== job.localId))
      if (job.canceled) return
      if (job.file.size === 0) {
        await extra.writeFile(job.id, job.view.path, '', 'base64')
      }
      else {
        const begin = await extra.beginUploadTask(job.id, job.view.path, job.file.size, sha256, job.file.name) as UploadTaskView
        const oldId = job.view.taskId
        own.delete(oldId)
        job.view.taskId = begin.taskId
        if (job.generation === generation && job.id === instanceId.value && !disposed) own.set(begin.taskId, job.view)
        jobs.delete(oldId)
        jobs.set(begin.taskId, job)
        if (job.canceled) { await abort(job); return }
        for (let offset = 0; offset < job.file.size; offset += UPLOAD_CHUNK_BYTES) {
          if (job.canceled) return
          const blob = job.file.slice(offset, offset + UPLOAD_CHUNK_BYTES)
          const result = await extra.uploadTaskChunk(job.id, begin.taskId, offset, blob) as { uploaded?: number }
          update(job, { uploaded: Number(result.uploaded ?? offset + blob.size) })
        }
        if (job.canceled) return
        await extra.commitUploadTask(job.id, begin.taskId)
      }
      ok = true
      update(job, { state: 'done', uploaded: job.file.size })
    }
    catch (error) {
      if (!job.canceled) {
        update(job, { state: 'failed', error: String((error as Error)?.message ?? '上传失败') })
        try { await abort(job) } catch { /* Node expiry cleans unfinished chunks. */ }
      }
    }
    finally {
      hashingIds.value = new Set([...hashingIds.value].filter(id => id !== job.localId))
      if (job.canceled && !ok) update(job, { state: 'canceled' })
      jobs.delete(job.view.taskId)
      job.settled(ok)
      active--
      drain()
    }
  }

  function drain() {
    while (active < 2 && queue.length) {
      const job = queue.shift()!
      if (job.canceled) { jobs.delete(job.view.taskId); job.settled(false); continue }
      active++
      void run(job)
    }
  }

  function startUpload(file: File, path: string, onSettled?: (ok: boolean) => void): Promise<boolean> {
    uploadRelativePath(path)
    if (!instanceId.value || disposed) return Promise.resolve(false)
    if (file.size > 2 * 1024 ** 3) throw new Error('单文件不能超过 2GiB，请使用 SFTP')
    const localId = `local-${crypto.randomUUID()}`
    const view: UploadTaskView = { taskId: localId, path, name: file.name, size: file.size, uploaded: 0, state: 'queued' }
    return new Promise(resolve => {
      const job: Job = { id: instanceId.value, localId, view, file, canceled: false, generation, settled: (ok) => { resolve(ok); if (job.generation === generation && !disposed) { try { onSettled?.(ok) } catch { /* A UI callback cannot stop queue cleanup. */ } } } }
      jobs.set(localId, job)
      own.set(localId, view)
      publish()
      queue.push(job)
      drain()
    })
  }

  async function cancelTask(taskId: string) {
    const job = jobs.get(taskId)
    if (job) {
      job.canceled = true
      update(job, { state: 'canceled', error: '' })
    }
    try {
      if (job) await abort(job)
      else await extra.cancelUploadTask(instanceId.value, taskId)
      void refresh()
    }
    catch (error) {
      if (job) update(job, { state: 'failed', error: `取消结果未确认：${(error as Error).message}` })
    }
  }

  watch(instanceId, () => {
    generation++
    for (const job of jobs.values()) if (job.generation !== generation) void cancelTask(job.view.taskId)
    own.clear()
    tasks.value = []
    void refresh()
  }, { immediate: true })
  onBeforeUnmount(() => {
    disposed = true
    clearInterval(timer)
    for (const job of jobs.values()) void cancelTask(job.view.taskId)
  })

  return { tasks, hasRunning, startUpload, cancelTask, refresh, isHashing: (id: string) => hashingIds.value.has(jobs.get(id)?.localId ?? id) }
}
