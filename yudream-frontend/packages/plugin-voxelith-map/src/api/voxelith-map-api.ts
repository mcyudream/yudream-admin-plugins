import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MapSummary } from '../types'

/**
 * 公开（匿名）接口薄封装。
 * sdk.http 已自动加前缀 /api/plugins/voxelith-map，且 sdk.http.url(path)
 * 返回可直接塞进 <img>/fetch/EventSource 的完整 URL。
 *
 * 查看器的 mapBaseUrl 直接用 sdk.http.url('/maps/' + id)，
 * 这样 vendored 的 loadManifest 与 TileManager 的瓦片拼接天然对上。
 */
export function createVoxelithMapApi(sdk: YuDreamPluginSdk) {
  const enc = encodeURIComponent
  return {
    /** GET /maps → { maps } */
    maps: () => sdk.http.get<{ maps: MapSummary[] }>('/maps'),
    /**
     * GET /maps/{id}/surface.png —— 地表栅格（渲染时导入本代，1px = 1 方块）：
     * 2D 地图的首选底图，逐方块精度且缩放不糊。
     */
    surfaceUrl: (mapId: string) => sdk.http.url(`/maps/${enc(mapId)}/surface.png`),
    /**
     * GET /maps/{id}/lod-atlas-data → { sha1, level, base64 }
     *
     * <p>2D 地图的取图通道：图集页字节以 base64 装进 JSON 直出。与清单同形的 JSON 传输
     * （无图片扩展名、无查询串），绕开个别网关对图片路径的静态文件规则——那正是
     * 「2D 地图一直加载中」的根源。</p>
     */
    /**
     * GET /maps/{id}/dimensions → { maps }
     *
     * <p>同一世界的各维度是各自独立的地图（各自渲染、各自瓦片），所以「切换维度」＝切到兄弟地图。
     * 只返回已发布的那些：没渲染完的维度切过去只会是空白。</p>
     */
    dimensions: (mapId: string) => sdk.http.get<{ maps: MapSummary[] }>(`/maps/${enc(mapId)}/dimensions`),
    /** 完整地图根 URL（瓦片 / manifest / 静态资源都挂在这里） */
    mapBaseUrl: (mapId: string) => sdk.http.url(`/maps/${enc(mapId)}`),
    /** markers.json 静态文件完整 URL（匿名可读） */
    markersJsonUrl: (mapId: string) => sdk.http.url(`/maps/${enc(mapId)}/markers.json`),
    /**
     * 全景图 PNG 完整 URL（匿名可读）。
     * 公开端按 ACTIVE_CACHE 回发，管理页重新烘焙后要带一个变化的 v 参数打破缓存。
     */
    panoramaUrl: (mapId: string, revision = 0) =>
      sdk.http.url(`/maps/${enc(mapId)}/panorama.png${revision > 0 ? `?v=${revision}` : ''}`),
    /** 全景图元数据（相机参数、尺寸等） */
    panoramaMetaUrl: (mapId: string) => sdk.http.url(`/maps/${enc(mapId)}/panorama.json`),
    /** 默认初始视角 JSON（管理员设置；未设置时为空对象） */
    viewJsonUrl: (mapId: string) => sdk.http.url(`/maps/${enc(mapId)}/view.json`),
    /** 列表封面截图 PNG（管理员保存默认视角时生成；未设置返回 404）。
     *  revision 传地图 updatedAt 等可变值打破浏览器缓存，重新保存封面后立即生效。 */
    coverUrl: (mapId: string, revision?: number | string) =>
      sdk.http.url(`/maps/${enc(mapId)}/cover.png${revision ? `?v=${encodeURIComponent(String(revision))}` : ''}`),
    /** 列表封面缩略图（小尺寸 JPEG）：画廊卡片只有几百像素宽，不该拉数 MB 的 PNG 截屏。
     *  revision 用封面版本号（coverRevision）——用地图 updatedAt 会让改名/渲染/改设置
     *  都把全部封面重新下载一遍。 */
    coverThumbUrl: (mapId: string, revision?: number | string) =>
      sdk.http.url(`/maps/${enc(mapId)}/cover-thumb.jpg${revision ? `?v=${encodeURIComponent(String(revision))}` : ''}`),
    /** 鸟瞰图 PNG（全景烘焙的副产物，匿名可读） */
    aerialUrl: (mapId: string, revision = 0) =>
      sdk.http.url(`/maps/${enc(mapId)}/aerial.png${revision > 0 ? `?v=${revision}` : ''}`),
  }
}

/** 从 markers.json 响应里取出标注集数组（兼容 {sets} 或直接数组两种形态）。 */
export function extractMarkerSets(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  if (payload && typeof payload === 'object' && Array.isArray((payload as { sets?: unknown[] }).sets)) {
    return (payload as { sets: unknown[] }).sets
  }
  return []
}
