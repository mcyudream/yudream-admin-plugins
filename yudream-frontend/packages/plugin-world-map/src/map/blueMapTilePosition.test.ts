import { describe, expect, it } from 'vitest'
import { blueMapTilePosition } from './blueMapTilePosition'

describe('blueMapTilePosition', () => {
  it('uses BlueMap v5 grid translate with tile-local PRBM geometry', () => {
    expect(blueMapTilePosition({ hiresTileSize: 32, hiresTileOffset: { x: 2, z: 2 } }, -3, -3))
      .toEqual({ x: -94, z: -94 })
  })
})
