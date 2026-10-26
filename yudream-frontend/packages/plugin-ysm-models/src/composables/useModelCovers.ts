import { onScopeDispose } from 'vue'
import { createCoverRenderer } from '../preview/cover-client'
import type { Bundle, ModelRow } from '../types'

export function useModelCovers(load: (id: string) => Promise<Bundle>) {
  const renderer = createCoverRenderer(), cache = new Map<string, Promise<string>>()
  let disposed = false
  // At most two full bundles/decoded textures in flight, even for a large viewport.
  let active = 0
  const queue: (() => void)[] = []
  function get(row: ModelRow): Promise<string> {
    const key = `${row.id}:${row.hash}`
    const hit = cache.get(key)
    if (hit) return hit
    const promise = new Promise<string>((resolve, reject) => {
      const run = () => {
        if (disposed) { reject(new Error('封面已取消')); return }
        active++
        load(row.id).then(bundle => renderer.render(bundle)).then(resolve, reject).finally(() => {
          active--; queue.shift()?.()
        })
      }
      if (active < 2) run(); else queue.push(run)
    })
    cache.set(key, promise)
    promise.catch(() => cache.delete(key))
    // Cache only small PNGs, bounded to the latest pages of this route.
    if (cache.size > 48) cache.delete(cache.keys().next().value!)
    return promise
  }
  onScopeDispose(() => { disposed = true; renderer.dispose(); cache.clear(); queue.splice(0).forEach(run => run()) })
  return get
}
