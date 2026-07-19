import { describe, expect, it } from 'vitest'
import { applyBlueMapSettings } from './BlueMapSettings'
import type { MapSettings } from '../types'

function settings(): MapSettings {
  return {
    id: 'map', name: 'Map', dimension: 'overworld', spawn: { x: 0, y: 64, z: 0 }, minY: -64, maxY: 320,
    hiresTileSize: 32, lowresTileSize: 512, lowresMaxLod: 3, generationId: 'generation', atlasUrl: 'textures/atlas.png',
  }
}

describe('applyBlueMapSettings', () => {
  it('accepts the Vector2i arrays emitted by BlueMap v5', () => {
    const value = settings()
    applyBlueMapSettings(value, {
      hires: { tileSize: [32, 32], translate: [2, 2] },
      lowres: { tileSize: [500, 500], lodCount: 3, lodFactor: 5 },
    })
    expect(value).toMatchObject({
      hiresTileSize: 32, hiresTileOffset: { x: 2, z: 2 }, lowresTileSize: 500,
      lowresMaxLod: 3, lowresLodFactor: 5, lowresMinLod: 1,
    })
  })

  it('rejects a malformed grid', () => {
    expect(() => applyBlueMapSettings(settings(), {
      hires: { tileSize: [32, 16], translate: [2, 2] },
      lowres: { tileSize: [500, 500], lodCount: 3, lodFactor: 5 },
    })).toThrow('tiles must be square')
  })
})
