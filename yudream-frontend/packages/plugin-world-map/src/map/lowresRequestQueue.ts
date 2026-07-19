export interface LowresRequest<T> {
  key: string
  priority: number
  value: T
}

/** Inserts by distance priority while retaining a strict queue budget during large overview jumps. */
export function enqueueLowresRequest<T>(queue: LowresRequest<T>[], request: LowresRequest<T>, maxQueued: number): boolean {
  if (queue.length >= maxQueued) {
    const worst = queue.reduce((index, item, candidate) =>
      item.priority > queue[index]!.priority ? candidate : index, 0)
    if (queue[worst]!.priority <= request.priority) return false
    queue.splice(worst, 1)
  }
  queue.push(request)
  queue.sort((a, b) => a.priority - b.priority || a.key.localeCompare(b.key))
  return true
}
