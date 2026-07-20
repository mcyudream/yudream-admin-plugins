import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import * as THREE from 'three'
import type { WorldMapSource } from '../map/types'
import type { HiresTile, MapMarkersResponse, MapSettings, MapSummary } from '../types'
import { decodeLowresImage } from '../map/lowresImageDecode'

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
    blueMapLowresIndexUrl: (mapId: string, generationId: string) =>
      sdk.http.url(`/maps/${encodeURIComponent(mapId)}/generations/${encodeURIComponent(generationId)}/lowres-index.json`),
  }
}

/** 基于契约 HTTP 接口的地图数据源 */
export function createHttpMapSource(sdk: YuDreamPluginSdk, mapId: string): WorldMapSource {
  const api = createWorldMapApi(sdk)
  const controller = new AbortController()
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
      const texture = await loadTexture(url, controller.signal)
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
      const response = await fetch(api.blueMapTexturesUrl(mapId, generationId), { signal: controller.signal })
      if (!response.ok) throw new Error(`BlueMap textures: HTTP ${response.status}`)
      return requireRawJsonPayload(await response.json(), 'BlueMap textures')
    },
    async loadBlueMapSettings() {
      const settings = await loadSettings()
      if (settings.renderer !== 'BLUEMAP' || !settings.blueMapSettingsUrl) {
        throw new Error('Map generation does not use BlueMap settings')
      }
      const response = await fetch(api.blueMapSettingsUrl(mapId, generationId), { signal: controller.signal })
      if (!response.ok) throw new Error(`BlueMap settings: HTTP ${response.status}`)
      return requireRawJsonPayload(await response.json(), 'BlueMap settings')
    },
    async loadBlueMapLowresIndex() {
      const settings = await loadSettings()
      if (settings.renderer !== 'BLUEMAP' || !settings.blueMapLowresIndexUrl) {
        return null
      }
      const response = await fetch(api.blueMapLowresIndexUrl(mapId, generationId), { signal: controller.signal })
      if (response.status === 404) {
        return null
      }
      if (!response.ok) throw new Error(`BlueMap lowres index: HTTP ${response.status}`)
      return optionalRawJsonPayload(await response.json(), 'BlueMap lowres index')
    },
    async fetchHiresTile(tx, tz, signal) {
      // gzip JSON：浏览器 fetch 自动处理 Content-Encoding: gzip
      await loadSettings()
      const res = await fetch(api.hiresTileUrl(mapId, generationId, tx, tz), { signal: combinedSignal(controller.signal, signal) })
      if (res.status === 404) {
        return null
      }
      if (!res.ok) {
        throw new Error(`hires tile ${tx},${tz}: HTTP ${res.status}`)
      }
      if (res.headers.get('Content-Type')?.includes('application/octet-stream')) {
        return res.arrayBuffer()
      }
      return toHiresTile(optionalRawJsonPayload(await res.json(), `hires tile ${tx},${tz}`))
    },
    lowresTileUrl: (lod, tx, tz) => generationId ? api.lowresTileUrl(mapId, generationId, lod, tx, tz) : null,
    fetchMarkers: () => api.markers(mapId),
    dispose: () => controller.abort(),
  }
}

/** Fetch-backed texture decoding lets a superseded map release its network request immediately. */
async function loadTexture(url: string, signal: AbortSignal): Promise<THREE.Texture> {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`atlas: HTTP ${response.status}`)
  const image = await decodeLowresImage(await response.blob())
  const texture = new THREE.Texture(image)
  texture.needsUpdate = true
  return texture
}

function combinedSignal(source: AbortSignal, request?: AbortSignal): AbortSignal {
  if (!request || request === source) return source
  if (typeof AbortSignal.any === 'function') return AbortSignal.any([source, request])
  const controller = new AbortController()
  const abort = () => controller.abort()
  source.addEventListener('abort', abort, { once: true })
  request.addEventListener('abort', abort, { once: true })
  return controller.signal
}

type PluginResponseEnvelope = {
  code: number
  data?: unknown
  message?: unknown
}

/**
 * The host wraps regular JSON PluginHttpResponse bodies, while streamed binary endpoints bypass
 * that wrapper. Keep raw fetches compatible with both response shapes.
 */
function unwrapPluginEnvelope(payload: unknown): { wrapped: boolean, data: unknown, message: string } {
  if (!payload || typeof payload !== 'object' || !('code' in payload) || typeof payload.code !== 'number') {
    return { wrapped: false, data: payload, message: '' }
  }
  const envelope = payload as PluginResponseEnvelope
  return {
    wrapped: true,
    data: envelope.data,
    message: typeof envelope.message === 'string' ? envelope.message : `code ${envelope.code}`,
  }
}

function requireRawJsonPayload(payload: unknown, resource: string): unknown {
  const result = unwrapPluginEnvelope(payload)
  if (!result.wrapped) return result.data
  if (result.data === null || result.data === undefined) {
    throw new Error(`${resource}: ${result.message}`)
  }
  return result.data
}

/** Missing optional tile/index records arrive as a host business envelope with HTTP 200. */
function optionalRawJsonPayload(payload: unknown, resource: string): unknown | null {
  const result = unwrapPluginEnvelope(payload)
  if (!result.wrapped) return result.data
  if (result.data === null || result.data === undefined) {
    void resource
    return null
  }
  return result.data
}

function toHiresTile(payload: unknown): HiresTile | null {
  if (payload === null || payload === undefined) return null
  if (!payload || typeof payload !== 'object'
    || !Array.isArray((payload as HiresTile).positions)
    || !Array.isArray((payload as HiresTile).indices)) {
    throw new Error('hires tile: invalid geometry payload')
  }
  return payload as HiresTile
}
