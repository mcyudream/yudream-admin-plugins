import type { MapSettings } from '../types'

/** Resolves a BlueMap PRBM tile-local mesh into its world-space grid position. */
export function blueMapTilePosition(settings: Pick<MapSettings, 'hiresTileSize' | 'hiresTileOffset'>, tx: number, tz: number) {
  const offset = settings.hiresTileOffset ?? { x: 0, z: 0 }
  return {
    x: tx * settings.hiresTileSize + offset.x,
    z: tz * settings.hiresTileSize + offset.z,
  }
}
