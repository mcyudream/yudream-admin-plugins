import type { Texture } from 'three'
import type { HiresTile, MapMarkersResponse, MapSettings } from '../types'

/**
 * 地图数据源抽象：MapViewer/TileManager 只依赖该接口，
 * 由 api 层（HTTP 实现）或 mock.ts（内置假数据）提供。
 */
export interface WorldMapSource {
  loadSettings: () => Promise<MapSettings>
  /** 加载贴图集（调用方负责 dispose） */
  loadAtlas: () => Promise<Texture>
  loadBlueMapTextures?: () => Promise<unknown>
  /** tile 不存在（404 / 空）时返回 null */
  fetchHiresTile: (tx: number, tz: number, signal?: AbortSignal) => Promise<HiresTile | ArrayBuffer | null>
  /** 返回 null 表示数据源不提供 lowres（如 mock） */
  lowresTileUrl: (lod: number, tx: number, tz: number) => string | null
  fetchMarkers: () => Promise<MapMarkersResponse>
}

export type CameraMode = 'orbit' | 'fly'
