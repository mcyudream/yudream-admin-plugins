import type { RenderTask } from '../types'

export function renderTaskProgress(task: RenderTask): number {
  if (typeof task.progressPercent === 'number') {
    return Math.min(100, Math.max(0, task.progressPercent))
  }
  if (!task.totalTiles) {
    return task.state === 'SUCCESS' ? 100 : 0
  }
  return Math.min(100, Math.round((task.doneTiles / task.totalTiles) * 100))
}
