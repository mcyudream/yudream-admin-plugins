import { rasterizeCover, type CoverPixels } from './cover-raster'
import type { Bundle } from '../types'

interface CoverJob { id: number; bundle: Bundle; texture: CoverPixels }
const worker = self as unknown as { onmessage: (event: MessageEvent<CoverJob>) => void; postMessage: (data: unknown, transfer?: Transferable[]) => void }
worker.onmessage = ({ data }) => {
  try {
    const result = rasterizeCover(data.bundle, data.texture)
    worker.postMessage({ id: data.id, result }, [result.pixels.buffer as ArrayBuffer])
  }
  catch (error) { worker.postMessage({ id: data.id, error: error instanceof Error ? error.message : '封面生成失败' }) }
}
