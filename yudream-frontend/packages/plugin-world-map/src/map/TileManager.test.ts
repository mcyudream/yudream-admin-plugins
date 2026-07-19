import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { TileManager } from './TileManager'
import type { HiresTile, MapSettings } from '../types'
import type { WorldMapSource } from './types'

const settings: MapSettings = {
  id: 'test',
  name: 'test',
  dimension: 'overworld',
  spawn: { x: 0, y: 64, z: 0 },
  minY: -64,
  maxY: 320,
  hiresTileSize: 32,
  lowresTileSize: 512,
  lowresMaxLod: 0,
  generationId: 'test',
  atlasUrl: '',
}

const tile: HiresTile = {
  x: 0,
  z: 0,
  positions: [0, 64, 0, 1, 64, 0, 0, 64, 1],
  indices: [0, 1, 2],
  uvs: [0, 0, 1, 0, 0, 1],
  colors: [1, 1, 1, 1, 1, 1, 1, 1, 1],
  ao: [1, 1, 1],
  blocklight: [0, 0, 0],
  skylight: [15, 15, 15],
}

describe('TileManager', () => {
  it('retains an in-flight terrain request when a nearby camera move still needs that tile', async () => {
    let resolveTile: ((value: HiresTile) => void) | undefined
    const source: WorldMapSource = {
      loadSettings: async () => settings,
      loadAtlas: async () => new THREE.Texture(),
      fetchHiresTile: () => new Promise<HiresTile>(resolve => { resolveTile = resolve }),
      lowresTileUrl: () => null,
      fetchMarkers: async () => ({ markerSets: [] }),
    }
    const parent = new THREE.Group()
    const manager = new TileManager(source, settings, new THREE.MeshBasicMaterial(), new THREE.ShaderMaterial(), parent, {
      hiresRadius: 1,
      maxConcurrent: 1,
    })
    const camera = new THREE.PerspectiveCamera()
    camera.position.set(0, 80, 0)
    camera.lookAt(0, 0, 0)

    manager.update(camera, new THREE.Vector3(0, 64, 0))
    expect(resolveTile).toBeTypeOf('function')

    // Tile (0,0) remains within radius one after crossing into the next tile column.
    manager.update(camera, new THREE.Vector3(32, 64, 0))
    resolveTile!(tile)
    await Promise.resolve()
    await Promise.resolve()

    expect(parent.getObjectByName('world-map-hires')?.children).toHaveLength(1)
    manager.dispose()
  })
})
