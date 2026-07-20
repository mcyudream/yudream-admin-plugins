import type { Vector3Like } from 'three'

export const SPAWN_VIEW_DISTANCE = 120
export const FLAT_CAMERA_HEIGHT = 2_000

export interface SpawnPosition extends Vector3Like {}

/** The default isometric perspective that BlueMap users expect when resetting a map. */
export function perspectiveSpawnPosition(spawn: SpawnPosition): SpawnPosition {
  const horizontal = SPAWN_VIEW_DISTANCE * Math.cos(Math.PI / 4)
  return {
    x: spawn.x + horizontal * Math.sin(Math.PI / 4),
    y: spawn.y + SPAWN_VIEW_DISTANCE * Math.sin(Math.PI / 4),
    z: spawn.z + horizontal * Math.cos(Math.PI / 4),
  }
}

export function flatSpawnPosition(spawn: SpawnPosition): SpawnPosition {
  return { x: spawn.x, y: spawn.y + FLAT_CAMERA_HEIGHT, z: spawn.z }
}
