import type { BlueMapLowresTileIndex } from '../types'

const MAX_LOD = 16
const MAX_COORDINATE = 1_000_000
const MAX_RANGES = 1_000_000

/** Parses the compact lowres coverage metadata emitted beside a BlueMap generation. */
export function parseBlueMapLowresIndex(payload: unknown): BlueMapLowresTileIndex | undefined {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return undefined
  const levels = (payload as { levels?: unknown }).levels
  if (!levels || typeof levels !== 'object' || Array.isArray(levels)) return undefined

  const result = new Map<number, Map<number, ReadonlyArray<readonly [number, number]>>>()
  let rangeCount = 0
  for (const [lodText, rows] of Object.entries(levels)) {
    const lod = Number(lodText)
    if (!Number.isInteger(lod) || lod < 1 || lod > MAX_LOD || !Array.isArray(rows)) return undefined
    const parsedRows = new Map<number, ReadonlyArray<readonly [number, number]>>()
    for (const row of rows) {
      if (!Array.isArray(row) || row.length !== 2 || !integer(row[0])) return undefined
      const z = row[0] as number
      if (Math.abs(z) > MAX_COORDINATE || !Array.isArray(row[1]) || parsedRows.has(z)) return undefined
      const ranges: Array<readonly [number, number]> = []
      let previousEnd = -Infinity
      for (const range of row[1]) {
        if (!Array.isArray(range) || range.length !== 2 || !integer(range[0]) || !integer(range[1])) return undefined
        const start = range[0] as number
        const end = range[1] as number
        if (Math.abs(start) > MAX_COORDINATE || Math.abs(end) > MAX_COORDINATE || start > end || start <= previousEnd) return undefined
        ranges.push([start, end])
        previousEnd = end
        rangeCount += 1
        if (rangeCount > MAX_RANGES) return undefined
      }
      if (ranges.length === 0) return undefined
      parsedRows.set(z, ranges)
    }
    result.set(lod, parsedRows)
  }
  return result.size > 0 ? result : undefined
}

/** Missing metadata is intentionally optimistic for backwards compatibility with old generations. */
export function hasBlueMapLowresTile(index: BlueMapLowresTileIndex | undefined, lod: number, x: number, z: number): boolean {
  if (!index) return true
  const ranges = index.get(lod)?.get(z)
  return ranges?.some(([start, end]) => x >= start && x <= end) ?? false
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}
