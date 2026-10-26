import { toRaw } from 'vue'
import type { Bundle } from '../types'
import type { CoverPixels } from './cover-raster'

/** One worker per page, terminated on disposal; no model data is cached across accounts. */
export function createCoverRenderer() {
  let worker: Worker | undefined, nextId = 0, disposed = false
  const pending = new Map<number, { resolve: (value: string) => void; reject: (error: Error) => void }>()
  function dispose() {
    disposed = true
    worker?.terminate()
    pending.forEach(p => p.reject(new Error('封面已取消')))
    pending.clear()
  }
  async function render(bundle: Bundle, textureName?: string): Promise<string> {
    if (disposed) throw new Error('封面已取消')
    const texture = bundle.textures.find(t => t.name === textureName) ?? bundle.textures[0]
    if (!texture) throw new Error('模型没有贴图')
    const image = new Image()
    image.src = `data:image/png;base64,${texture.png}`
    await image.decode()
    if (disposed) throw new Error('封面已取消')
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth; canvas.height = image.naturalHeight
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('当前浏览器无法创建二维画布')
    context.drawImage(image, 0, 0)
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
    if (!worker) {
      worker = new Worker(new URL('./cover-worker.ts', import.meta.url), { type: 'module' })
      worker.onmessage = ({ data }: MessageEvent<{ id: number; result?: CoverPixels; error?: string }>) => {
        const task = pending.get(data.id)
        if (!task) return
        pending.delete(data.id)
        if (!data.result) { task.reject(new Error(data.error ?? '封面生成失败')); return }
        try {
          const output = document.createElement('canvas')
          output.width = data.result.width; output.height = data.result.height
          const ctx = output.getContext('2d')
          if (!ctx) throw new Error('当前浏览器无法创建二维画布')
          ctx.putImageData(new ImageData(new Uint8ClampedArray(data.result.pixels), output.width, output.height), 0, 0)
          task.resolve(output.toDataURL('image/png'))
        }
        catch (e) { task.reject(e instanceof Error ? e : new Error('封面生成失败')) }
      }
      worker.onerror = () => { pending.forEach(p => p.reject(new Error('封面生成失败，请重试'))); pending.clear(); worker?.terminate(); worker = undefined }
    }
    return new Promise((resolve, reject) => {
      const id = ++nextId
      pending.set(id, { resolve, reject })
      try { worker!.postMessage({ id, bundle: toRaw(bundle), texture: { width: canvas.width, height: canvas.height, pixels } }, [pixels.buffer]) }
      catch (error) { pending.delete(id); reject(error instanceof Error ? error : new Error('封面生成失败')) }
    })
  }
  return { render, dispose }
}
