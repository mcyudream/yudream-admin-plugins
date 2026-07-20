import type { MapSettings } from '../types'

interface BlueMapVector {
  x: number
  y: number
}

interface BlueMapSettingsMetadata {
  hires?: { tileSize?: unknown, translate?: unknown }
  lowres?: { tileSize?: unknown, lodCount?: unknown, lodFactor?: unknown }
}

/** Applies BlueMap v5's settings.json grid metadata to the public viewer settings. */
export function applyBlueMapSettings(settings: MapSettings, metadata: unknown): void {
  if (!metadata || typeof metadata !== 'object') throw new Error('Invalid BlueMap settings metadata')
  const root = metadata as BlueMapSettingsMetadata
  const hires = vector(root.hires?.tileSize, 'hires.tileSize', true)
  const lowres = vector(root.lowres?.tileSize, 'lowres.tileSize', true)
  const translate = vector(root.hires?.translate, 'hires.translate', false)
  const lodCount = positiveInt(root.lowres?.lodCount, 'lowres.lodCount')
  const lodFactor = positiveInt(root.lowres?.lodFactor, 'lowres.lodFactor')
  if (hires.x !== hires.y || lowres.x !== lowres.y) throw new Error('BlueMap tiles must be square')
  settings.hiresTileSize = hires.x
  settings.hiresTileOffset = { x: translate.x, z: translate.y }
  settings.lowresTileSize = lowres.x
  settings.lowresMaxLod = lodCount
  settings.lowresLodFactor = lodFactor
  settings.lowresMinLod = 1
}

function vector(value: unknown, field: string, positive: boolean): BlueMapVector {
  const pair = Array.isArray(value)
    ? value
    : value && typeof value === 'object'
      ? [(value as { x?: unknown }).x, (value as { y?: unknown }).y]
      : []
  if (pair.length !== 2) throw new Error(`Invalid BlueMap ${field}`)
  const x = integer(pair[0], field + '.x', positive)
  const y = integer(pair[1], field + '.y', positive)
  return { x, y }
}

function positiveInt(value: unknown, field: string): number {
  return integer(value, field, true)
}

function integer(value: unknown, field: string, positive: boolean): number {
  const lower = positive ? 1 : -4096
  if (typeof value !== 'number' || !Number.isInteger(value) || value < lower || value > 4096) {
    throw new Error(`Invalid BlueMap ${field}`)
  }
  return value
}
