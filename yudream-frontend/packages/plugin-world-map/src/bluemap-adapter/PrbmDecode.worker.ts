import { decodePrbmData } from './PrbmDecoder'
import type { PrbmGeometryData } from './PrbmDecoder'

interface PrbmWorkerScope {
  onmessage: ((event: MessageEvent<{ id: number, buffer: ArrayBuffer }>) => void) | null
  postMessage: (message: unknown, transfer?: Transferable[]) => void
}

const workerScope = self as unknown as PrbmWorkerScope

workerScope.onmessage = (event: MessageEvent<{ id: number, buffer: ArrayBuffer }>) => {
  const { id, buffer } = event.data
  try {
    const data = decodePrbmData(buffer)
    const transfer = uniqueBuffers(data)
    workerScope.postMessage({ id, data }, transfer)
  }
  catch (error) {
    workerScope.postMessage({ id, error: error instanceof Error ? error.message : 'PRBM decode failed' })
  }
}

function uniqueBuffers(data: PrbmGeometryData): Transferable[] {
  const buffers = new Set<ArrayBuffer>()
  for (const attribute of data.attributes) buffers.add(attribute.buffer)
  if (data.index) buffers.add(data.index.buffer)
  return [...buffers]
}
