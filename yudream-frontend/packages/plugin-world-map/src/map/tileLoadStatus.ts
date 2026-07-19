export interface TileLoadStatus {
  hiresQueued: number
  hiresLoading: number
  lowresQueued: number
  lowresLoading: number
  retrying: number
}

export const EMPTY_TILE_LOAD_STATUS: TileLoadStatus = {
  hiresQueued: 0,
  hiresLoading: 0,
  lowresQueued: 0,
  lowresLoading: 0,
  retrying: 0,
}

/** A compact operational label that distinguishes visible terrain from overview imagery. */
export function tileLoadMessage(status: TileLoadStatus): string | null {
  const terrain = status.hiresQueued + status.hiresLoading
  const overview = status.lowresQueued + status.lowresLoading
  const parts: string[] = []
  if (terrain > 0) parts.push(`地形 ${terrain}`)
  if (overview > 0) parts.push(`概览 ${overview}`)
  if (status.retrying > 0) parts.push(`重试 ${status.retrying}`)
  return parts.length > 0 ? `加载 ${parts.join(' · ')}` : null
}
