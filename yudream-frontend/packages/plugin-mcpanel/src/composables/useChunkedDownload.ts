import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { collectFileChunks } from '../utils/chunkedDownload.ts'
import type { FileChunk } from '../utils/textFileAccess.ts'

export function useChunkedDownload() {
  const active = ref(false)
  const progress = ref(0)
  const fileName = ref('')
  let controller: AbortController | null = null

  function cancel() { controller?.abort() }
  async function start(name: string, fetchChunk: (offset: number, length: number) => Promise<unknown>) {
    if (active.value) return
    const own = new AbortController()
    controller = own
    active.value = true
    progress.value = 0
    fileName.value = name
    try {
      const parts = await collectFileChunks(async (offset, length) => await fetchChunk(offset, length) as FileChunk, {
        signal: own.signal,
        onProgress: (loaded, total) => { progress.value = total ? Math.round(loaded / total * 100) : 100 },
      })
      own.signal.throwIfAborted()
      const url = URL.createObjectURL(new Blob(parts as BlobPart[], { type: 'application/octet-stream' }))
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = name
      anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    }
    catch (error) {
      if (!own.signal.aborted) throw error
    }
    finally {
      if (controller === own) { controller = null; active.value = false }
    }
  }
  if (getCurrentScope()) onScopeDispose(cancel)
  return { active, progress, fileName, start, cancel }
}
