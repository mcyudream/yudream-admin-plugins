import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import * as THREE from 'three'
import type { WorldMapSource } from '../map/types'
import type { HiresTile, MapMarkersResponse, MapSettings, MapSummary } from '../types'

/** 契约 §2 公开接口封装（sdk.http 已带插件命名空间前缀） */
export function createWorldMapApi(sdk: YuDreamPluginSdk) {
  return {
    maps: () => sdk.http.get<{ maps: MapSummary[] }>('/maps'),
    settings: (mapId: string) => sdk.http.get<MapSettings>(`/maps/${encodeURIComponent(mapId)}/settings`),
    markers: (mapId: string) => sdk.http.get<MapMarkersResponse>(`/maps/${encodeURIComponent(mapId)}/markers`),
    hiresTileUrl: (mapId: string, generationId: string, tx: number, tz: number) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/generations/${encodeURIComponent(generationId)}/tiles/hires/${tx}/${tz}`),
    lowresTileUrl: (mapId: string, generationId: string, lod: number, tx: number, tz: number) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/generations/${encodeURIComponent(generationId)}/tiles/lowres/${lod}/${tx}/${tz}`),
    blueMapTexturesUrl: (mapId: string, generationId: string) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/generations/${encodeURIComponent(generationId)}/textures.json`),
    blueMapSettingsUrl: (mapId: string, generationId: string) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/generations/${encodeURIComponent(generationId)}/settings.json`),
  }
}

/** 基于契约 HTTP 接口的地图数据源 */
export function createHttpMapSource(sdk: YuDreamPluginSdk, mapId: string): WorldMapSource {
  const api = createWorldMapApi(sdk)
  let settingsPromise: Promise<MapSettings> | null = null
  let generationId = ''
  const loadSettings = (): Promise<MapSettings> => {
    settingsPromise ??= api.settings(mapId).then((settings) => {
      generationId = settings.generationId
      return settings
    })
    return settingsPromise
  }

  return {
    loadSettings,
    async loadAtlas() {
      const settings = await loadSettings()
      // atlasUrl 相对 /maps/{id}/（契约 §3，如 textures/atlas.png）
      const url = sdk.http.url(`/maps/${encodeURIComponent(mapId)}/${settings.atlasUrl}`)
      const texture = await new THREE.TextureLoader().loadAsync(url)
      // atlas 为像素贴图集：近景 Nearest 保持像素风，远景走 mipmap+各向异性避免闪点噪声；
      // 图集单元带 1px 边缘复制，mipmap 出血可控
      texture.magFilter = THREE.NearestFilter
      texture.minFilter = THREE.LinearMipmapLinearFilter
      texture.generateMipmaps = true
      texture.anisotropy = 8
      texture.colorSpace = THREE.SRGBColorSpace
      return texture
    },
    async loadBlueMapTextures() {
      const settings = await loadSettings()
      if (settings.renderer !== 'BLUEMAP' || !settings.blueMapTexturesUrl) {
        throw new Error('Map generation does not use BlueMap textures')
      }
      const response = await fetch(api.blueMapTexturesUrl(mapId, generationId))
      if (!response.ok) throw new Error(`BlueMap textures: HTTP ${response.status}`)
      return response.json()
    },
    async loadBlueMapSettings() {
      const settings = await loadSettings()
      if (settings.renderer !== 'BLUEMAP' || !settings.blueMapSettingsUrl) {
        throw new Error('Map generation does not use BlueMap settings')
      }
      const response = await fetch(api.blueMapSettingsUrl(mapId, generationId))
      if (!response.ok) throw new Error(`BlueMap settings: HTTP ${response.status}`)
      return response.json()
    },
    async fetchHiresTile(tx, tz, signal) {
      // gzip JSON：浏览器 fetch 自动处理 Content-Encoding: gzip
      await loadSettings()
      const res = await fetch(api.hiresTileUrl(mapId, generationId, tx, tz), { signal })
      if (res.status === 404) {
        return null
      }
      if (!res.ok) {
        throw new Error(`hires tile ${tx},${tz}: HTTP ${res.status}`)
      }
      if (res.headers.get('Content-Type')?.includes('application/octet-stream')) {
        return res.arrayBuffer()
      }
      return (await res.json()) as HiresTile
    },
    lowresTileUrl: (lod, tx, tz) => generationId ? api.lowresTileUrl(mapId, generationId, lod, tx, tz) : null,
    fetchMarkers: () => api.markers(mapId),
  }
}
