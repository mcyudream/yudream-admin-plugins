/**
 * world-map 渲染契约类型（对齐 yudream-plugin-world-map/CONTRACT.md §2–§4）
 */

/** §2 GET /maps 返回的地图摘要（后端字段以后端实现为准，前端只依赖 id/name） */
export interface MapSummary {
  id: string
  name: string
  dimension?: string
  renderedAt?: number
}

/** §3 MapSettings */
export interface MapSettings {
  id: string
  name: string
  dimension: string
  spawn: { x: number, y: number, z: number }
  minY: number
  maxY: number
  /** hires tile 边长（方块数），固定 32 */
  hiresTileSize: number
  /** 低清 tile 像素边长，固定 512；lodN 每 tile 覆盖 512 * 2^N 方块 */
  lowresTileSize: number
  /** 最高 lod 级别（含） */
  lowresMaxLod: number
  /** 当前已发布的不可变渲染版本 */
  generationId: string
  /** 相对 /maps/{id}/ 的 atlas 路径，如 textures/atlas.png */
  atlasUrl: string
  /** Active generation geometry and texture contract. Older generations omit this and use YUDREAM. */
  renderer?: 'YUDREAM' | 'BLUEMAP'
  /** BlueMap v5 textures.json, set only for BLUEMAP generations. */
  blueMapTexturesUrl?: string | null
  renderedAt?: number
}

/** §4 hires tile 几何段（不透明/半透明共用结构） */
export interface HiresTileGeometry {
  /** float32，xyz 三元组，世界绝对坐标 */
  positions: number[]
  /** uint32 三角形索引（3 的倍数） */
  indices: number[]
  /** float32，uv 二元组，已映射到 atlas 空间 [0,1] */
  uvs: number[]
  /** float32，rgb 三元组，顶点染色（无染色为 1,1,1） */
  colors: number[]
  /** float32 0..1，环境光遮蔽×面方向明暗系数（1 = 全亮） */
  ao: number[]
  /** float 0..15，逐顶点光照（平滑光照，可为非整数） */
  blocklight: number[]
  skylight: number[]
}

/** §4 hires tile（gzip JSON，fetch 自动解压） */
export interface HiresTile extends HiresTileGeometry {
  x: number
  z: number
  /** 半透明段（水面等），前端用半透明材质单独渲染 */
  translucent?: HiresTileGeometry
}

/** §2 GET /maps/{id}/markers（一期返回空集占位，字段为二期预留宽松结构） */
export interface MapMarker {
  id?: string
  type?: string
  label?: string
  position?: { x: number, y: number, z: number }
  points?: Array<{ x: number, y: number, z: number }>
  color?: string
  [key: string]: unknown
}

export interface MapMarkerSet {
  id?: string
  label?: string
  defaultVisible?: boolean
  markers?: MapMarker[]
  [key: string]: unknown
}

export interface MapMarkersResponse {
  markerSets: MapMarkerSet[]
}

/* ---------- 管理端（契约 §2 管理接口，permission = plugin:world-map:manage） ---------- */

export type MapDimension = 'overworld' | 'nether' | 'the_end'

export type MapAdminState = 'EMPTY' | 'RENDERING' | 'READY' | 'CANCELLED' | 'FAILED'

/** GET /admin/maps 返回的地图管理视图 */
export interface MapAdmin {
  id: string
  name: string
  dimension: string
  state: MapAdminState
  hiresTiles: number
  lowresTiles: number
  createdAt?: number
  renderedAt?: number
  message?: string
}

/** POST /admin/maps 请求体；worldFileId/clientJarFileId 来自 sdk.files 上传 */
export interface CreateMapPayload {
  name: string
  dimension: MapDimension
  worldFileId: string
  clientJarFileId?: string
  stripNetherCeiling?: boolean
}

export type RenderTaskState = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'CANCELLED' | 'FAILED'

export type RenderPhase = 'IMPORT' | 'EXTRACT' | 'ASSETS' | 'HIRES' | 'LOWRES' | 'PUBLISH'

/** 渲染任务（POST /admin/maps/{id}/render、GET /admin/tasks） */
export interface RenderTask {
  id: string
  mapId: string
  state: RenderTaskState
  phase?: RenderPhase
  progressPercent?: number
  totalTiles: number
  doneTiles: number
  message?: string
  createdAt?: number
  startedAt?: number
  finishedAt?: number
  error?: string
}
