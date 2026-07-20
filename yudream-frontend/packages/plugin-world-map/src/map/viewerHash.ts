import type { MapMarkerSet } from '../types'
import type { MapViewMode } from './types'

export interface SharedMapView {
  mapId: string
  viewMode: MapViewMode
  position: { x: number, y: number, z: number }
  target: { x: number, y: number, z: number }
  zoom?: number
  layerVisibility: Record<string, boolean>
}

/** Reads the map selection before source loading, so a shared map is not replaced by the default. */
export function mapIdFromHash(hash: string): string | null {
  const mapId = new URLSearchParams(hash.replace(/^#/, '')).get('map')
  return mapId?.trim() || null
}

/** Applies only known layer ids, keeping a malformed or stale shared hash harmless. */
export function layerVisibilityFromHash(hash: string, sets: readonly MapMarkerSet[]): Record<string, boolean> {
  const defaults = Object.fromEntries(sets.map(set => [set.id ?? '', set.defaultVisible !== false]))
  const encoded = new URLSearchParams(hash.replace(/^#/, '')).get('layers')
  if (!encoded) return defaults
  try {
    const parsed = JSON.parse(encoded) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return defaults
    for (const set of sets) {
      const id = set.id ?? ''
      const visible = (parsed as Record<string, unknown>)[id]
      if (typeof visible === 'boolean') defaults[id] = visible
    }
  }
  catch {
    // A link with an invalid optional layers payload should still open the map.
  }
  return defaults
}

export function viewerHash(view: SharedMapView): string {
  const params = new URLSearchParams()
  params.set('map', view.mapId)
  params.set('view', view.viewMode)
  params.set('pos', `${view.position.x.toFixed(1)},${view.position.y.toFixed(1)},${view.position.z.toFixed(1)}`)
  params.set('target', `${view.target.x.toFixed(1)},${view.target.y.toFixed(1)},${view.target.z.toFixed(1)}`)
  if (view.zoom !== undefined) params.set('zoom', view.zoom.toFixed(3))
  if (Object.keys(view.layerVisibility).length > 0) params.set('layers', JSON.stringify(view.layerVisibility))
  return `#${params.toString()}`
}
