/**
 * Detail terrain grows quadratically with the tile radius. Keep the interactive setting inside a
 * range that remains practical for browser memory and PRBM decode throughput.
 */
export const MIN_HIRES_RADIUS = 2
export const DEFAULT_HIRES_RADIUS = 4
export const MAX_HIRES_RADIUS = 6

/** Overview tiles are cheaper than PRBM terrain, but large coverage still costs network and VRAM. */
export const MIN_LOWRES_COVERAGE = 1
export const DEFAULT_LOWRES_COVERAGE = 1.5
export const MAX_LOWRES_COVERAGE = 2.5

export function normalizeHiresRadius(value?: number): number {
  if (!Number.isFinite(value)) return DEFAULT_HIRES_RADIUS
  return Math.round(Math.min(MAX_HIRES_RADIUS, Math.max(MIN_HIRES_RADIUS, value!)))
}

export function normalizeLowresCoverage(value?: number): number {
  if (!Number.isFinite(value)) return DEFAULT_LOWRES_COVERAGE
  return Math.min(MAX_LOWRES_COVERAGE, Math.max(MIN_LOWRES_COVERAGE, value!))
}
