import type { MapViewMode } from './types'

export const PERSPECTIVE_FOG = { near: 200, far: 900 } as const
export const FLAT_FOG = { near: 4_000, far: 10_000 } as const

/** Keeps overview terrain outside the perspective fog envelope. */
export function fogForViewMode(mode: MapViewMode) {
  return mode === 'flat' ? FLAT_FOG : PERSPECTIVE_FOG
}
