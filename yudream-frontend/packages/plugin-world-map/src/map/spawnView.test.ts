import { describe, expect, it } from 'vitest'
import { FLAT_CAMERA_HEIGHT, flatSpawnPosition, perspectiveSpawnPosition, SPAWN_VIEW_DISTANCE } from './spawnView'

describe('spawn view positioning', () => {
  it('uses an isometric perspective offset from the published spawn', () => {
    const position = perspectiveSpawnPosition({ x: 10, y: 64, z: -4 })
    expect(position.x).toBeCloseTo(10 + SPAWN_VIEW_DISTANCE / 2)
    expect(position.y).toBeCloseTo(64 + SPAWN_VIEW_DISTANCE / Math.sqrt(2))
    expect(position.z).toBeCloseTo(-4 + SPAWN_VIEW_DISTANCE / 2)
  })

  it('keeps a flat reset directly over the published spawn', () => {
    expect(flatSpawnPosition({ x: 10, y: 64, z: -4 })).toEqual({ x: 10, y: 64 + FLAT_CAMERA_HEIGHT, z: -4 })
  })
})
