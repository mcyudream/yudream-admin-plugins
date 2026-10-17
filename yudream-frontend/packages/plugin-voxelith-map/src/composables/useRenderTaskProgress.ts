import type { RenderTask } from '../types'

/** 渲染进度百分比：优先用后端 percent，否则按瓦片数比值估算。 */
export function renderTaskProgress(task: RenderTask): number {
  if (typeof task.percent === 'number' && task.percent > 0) {
    return Math.min(100, Math.max(0, Math.round(task.percent)))
  }
  if (!task.totalTiles) {
    return task.state === 'SUCCEEDED' ? 100 : 0
  }
  return Math.min(100, Math.round((task.completedTiles / task.totalTiles) * 100))
}

/** 任务是否仍在进行中（用于轮询判定）。 */
export function isTaskActive(task: RenderTask | null): boolean {
  return task !== null && (task.state === 'PENDING' || task.state === 'RUNNING')
}
