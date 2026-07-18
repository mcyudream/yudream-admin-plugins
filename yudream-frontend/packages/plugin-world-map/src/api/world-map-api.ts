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
    hiresTileUrl: (mapId: string, tx: number, tz: number) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/tiles/hires/${tx}/${tz}`),
    lowresTileUrl: (mapId: string, lod: number, tx: number, tz: number) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/tiles/lowres/${lod}/${tx}/${tz}`),
  }
}

/** 基于契约 HTTP 接口的地图数据源 */
export function createHttpMapSource(sdk: YuDreamPluginSdk, mapId: string): WorldMapSource {
  const api = createWorldMapApi(sdk)
  let settingsPromise: Promise<MapSettings> | null = null
  const loadSettings = (): Promise<MapSettings> => {
    settingsPromise ??= api.settings(mapId)
    return settingsPromise
  }
  /** 以 renderedAt 作为资产版本号：重渲染后 URL 变化，浏览器缓存自然失效 */
  const versioned = async (url: string): Promise<string> => {
    const settings = await loadSettings()
    const v = settings.renderedAt ?? 0
    return `${url}${url.includes('?') ? '&' : '?'}v=${v}`
  }

  return {
    loadSettings,
    async loadAtlas() {
      const settings = await loadSettings()
      // atlasUrl 相对 /maps/{id}/（契约 §3，如 textures/atlas.png）
      const url = await versioned(sdk.http.url(`/maps/${encodeURIComponent(mapId)}/${settings.atlasUrl}`))
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
    async fetchHiresTile(tx, tz, signal) {
      // gzip JSON：浏览器 fetch 自动处理 Content-Encoding: gzip
      const res = await fetch(await versioned(api.hiresTileUrl(mapId, tx, tz)), { signal })
      if (res.status === 404) {
        return null
      }
      if (!res.ok) {
        throw new Error(`hires tile ${tx},${tz}: HTTP ${res.status}`)
      }
      return (await res.json()) as HiresTile
    },
    lowresTileUrl: (lod, tx, tz) => api.lowresTileUrl(mapId, lod, tx, tz),
    fetchMarkers: () => api.markers(mapId),
  }
}
