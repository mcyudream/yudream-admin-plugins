import { createPrbmGeometry, decodePrbmData } from './PrbmDecoder'
import type { PrbmGeometryData } from './PrbmDecoder'

interface PendingDecode {
  resolve: (data: PrbmGeometryData) => void
  reject: (error: Error) => void
}

interface DecodeWorkerResponse {
  id: number
  data?: PrbmGeometryData
  error?: string
}

/** A small pool prevents large PRBM parsing from blocking camera input while bounding decode pressure. */
export class PrbmDecodePool {
  private readonly workers: Worker[] = []
  private readonly pending = new Map<number, PendingDecode>()
  private nextWorker = 0
  private nextId = 1
  private disposed = false

  constructor(workerCount = 2) {
    if (typeof Worker !== 'function') return
    for (let index = 0; index < workerCount; index += 1) {
      const worker = new Worker(new URL('./PrbmDecode.worker.ts', import.meta.url), { type: 'module' })
      worker.addEventListener('message', this.onMessage)
      worker.addEventListener('error', this.onError)
      this.workers.push(worker)
    }
  }

  async decode(buffer: ArrayBuffer) {
    if (this.disposed) throw new Error('PRBM decoder was disposed')
    if (this.workers.length === 0) return createPrbmGeometry(decodePrbmData(buffer))
    const id = this.nextId++
    const worker = this.workers[this.nextWorker++ % this.workers.length]!
    const data = await new Promise<PrbmGeometryData>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      worker.postMessage({ id, buffer }, [buffer])
    })
    if (this.disposed) throw new Error('PRBM decoder was disposed')
    return createPrbmGeometry(data)
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    for (const worker of this.workers) {
      worker.removeEventListener('message', this.onMessage)
      worker.removeEventListener('error', this.onError)
      worker.terminate()
    }
    this.workers.length = 0
    for (const pending of this.pending.values()) {
      pending.reject(new Error('PRBM decoder was disposed'))
    }
    this.pending.clear()
  }

  private onMessage = (event: MessageEvent<DecodeWorkerResponse>): void => {
    const { id, data, error } = event.data
    const pending = this.pending.get(id)
    if (!pending) return
    this.pending.delete(id)
    if (error || !data) {
      pending.reject(new Error(error ?? 'PRBM decode failed'))
      return
    }
    pending.resolve(data)
  }

  private onError = (): void => {
    for (const pending of this.pending.values()) {
      pending.reject(new Error('PRBM decode worker failed'))
    }
    this.pending.clear()
  }
}
