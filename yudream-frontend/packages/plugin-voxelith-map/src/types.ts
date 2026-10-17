/**
 * voxelith-map 前后端契约类型定义（与后端 record 逐字对应）。
 * 长整型 ID / Snowflake 一律以 string 传输，禁止 Number(id)。
 */

export interface MapSummary {
  id: string
  name: string
  dimension: string
  ready: boolean
  version: string
  tileCount: number
  boundsMin: number[]
  boundsMax: number[]
  center: number[]
  spawn: number[]
  generatedAt?: string
}

export type MapAdminState =
  | 'EMPTY'
  | 'QUEUED'
  | 'PREPARING'
  | 'RENDERING'
  | 'READY'
  | 'CANCELED'
  | 'FAILED'
  | 'DELETING'
  | 'DELETED'

export interface MapRegion {
  minX: number
  minZ: number
  maxX: number
  maxZ: number
  widthBlocks: number
  depthBlocks: number
  chunkCount: number
}

export interface MapRenderSettings {
  minY: number
  maxLevel: number
  lodAtlas: boolean
  meshopt: boolean
  maxChunks: number
  batchChunks: number
  skipHarvest: boolean
  /**
   * 采集用的游戏版本。空串 = 交给引擎自动判定，判据顺序：
   * 显式设置 → 资源包文件名 → 存档版本 → 引擎兜底常量。
   */
  mcVersion: string
  loaderVersion: string
}

/** 绑定到地图项目的一个模组 jar（与后端 MapAdminDTO.ModJarDTO 逐字对应） */
export interface ModJar {
  fileId: string
  /** 上传时的原始文件名（可为空，仅展示用） */
  name: string
}

export interface MapAdmin {
  id: string
  name: string
  worldName: string
  worldFileId: string
  clientJarFileId: string
  /**
   * 上传时的客户端 jar 原名。宿主对象存储不保留原名（objectKey 与文件名无关），
   * 而引擎要靠文件名里的版本号判定采集版本，所以插件自己记一份。
   */
  clientJarName: string
  hasClientJar: boolean
  /** 绑定的模组 jar 列表（渲染时叠在 client jar 之上） */
  /** 模组包 zip 的平台文件 id（渲染前解包成 jar；整包只占一个存储对象） */
  modsPackFileId?: string | null
  /** 模组包原始文件名（展示用） */
  modsPackName?: string | null
  modJars: ModJar[]
  sidecarUploadId: string | null
  dimension: string
  spawn: number[]
  state: MapAdminState
  activeGenerationId: string | null
  publishedGenerations: string[]
  region: MapRegion | null
  settings: MapRenderSettings
  scaleDescription: string
  boundsMin: number[]
  boundsMax: number[]
  lastError: string | null
  createdAt: string | null
  updatedAt: string | null
  /** 封面版本号：0 = 没有封面，>0 = 封面版本（保存封面时的时间戳）。
   *  列表据此拼缓存指纹并判断有没有封面——不再单独下发 hasCover，免得列表为每张地图多查一次存储 */
  coverRevision?: number
  /** 是否已归档（归档后管理列表/查看器默认隐藏）；旧响应可能没有该字段 */
  archived?: boolean
  /** 来源 MC 面板实例 id（经「从实例导入」创建时记录）；旧响应可能没有该字段 */
  sourceInstanceId?: string | null
  /**
   * 世界分组键：同一次导入/上传产生的各维度地图共用一个值。
   *
   * <p>管理端据此把「一个存档」显示成一行、维度收纳在行内切换。老项目（分组字段引入前创建）
   * 读出来是存档文件 id 派生的键，语义一致，前端不需要区分。</p>
   */
  worldGroup?: string | null
}

/** 默认初始视角：相机位置 + 注视点（世界方块坐标） */
export interface MapDefaultView {
  x: number
  y: number
  z: number
  tx: number
  ty: number
  tz: number
}

/** 原版客户端 jar 库条目（管理员预上传，新建地图时按版本选用） */
export interface ClientJarEntry {
  version: string
  fileId: string
  fileName: string
  sizeBytes: number
  createdAt: string | null
}

/** MC 面板实例摘要（快捷导入用） */
export interface PanelInstanceSummary {
  id: string
  name: string
  mcVersion: string
  state: string
}

/** PUT /admin/client-jars 请求体 */
export interface PutClientJarPayload {
  version: string
  fileId: string
  fileName?: string
  sizeBytes?: number
}

/** PUT /admin/maps/{id}/default-view 请求体 */
export interface SetDefaultViewPayload extends MapDefaultView {
  /** 当前画面 PNG 截图（base64，不带 dataURL 前缀）；保存后作为列表页展示图 */
  coverPngBase64?: string
}

/** POST /admin/maps 请求体 */
export interface CreateMapPayload {
  name: string
  worldFileId: string
  worldName: string
  clientJarFileId: string
  /** 客户端 jar 原名（含版本号，如 client-1.21.1.jar），引擎据它自动判定采集版本 */
  clientJarName: string
  /** 模组包 zip 的平台文件 id（可省略）；渲染前由后端解包成 jar */
  modsPackFileId?: string
  /** 模组包原始文件名（展示用） */
  modsPackName?: string
  /** 模组 jar 列表（可省略 / 空 = 纯原版） */
  modJars?: ModJar[]
  dimension: string
  settings: MapRenderSettings
  /**
   * true = 按存档包内的并列维度（world / world_nether / world_the_end）一次建出全部维度地图，
   * 共享同一份存档与同一个世界分组；省略 / false = 只建 dimension 指定的那一张。
   */
  allDimensions?: boolean
}

/** POST/PUT /admin/maps/{id} 的请求体（全部可选） */
export interface UpdateMapPayload {
  name?: string
  dimension?: string
  worldName?: string
  clientJarFileId?: string
  clientJarName?: string
  /**
   * 模组包 zip 的平台文件 id。语义特殊：省略 = 不改动；空串 = 清除（换回纯原版或逐个 jar）。
   */
  modsPackFileId?: string
  /** 模组包原始文件名（展示用） */
  modsPackName?: string
  /**
   * 模组 jar 列表。语义特殊：省略 = 不改动；传数组（含空数组）= 整体替换。
   * 清空模组就传 []。
   */
  modJars?: ModJar[]
  settings?: Partial<MapRenderSettings>
  regionMinX?: number
  regionMinZ?: number
  regionMaxX?: number
  regionMaxZ?: number
  clearRegion?: boolean
}

export type RenderTaskState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'

export type RenderPhase =
  | 'QUEUED'
  | 'PREPARE'
  | 'IMPORT'
  | 'EXTRACT'
  | 'ASSETS'
  | 'HIRES'
  | 'LOD'
  | 'PUBLISH'

export interface RenderTask {
  id: string
  /** 任务类型：RENDER / IMPORT / SYNC / DELETE（任务中心按它区分展示） */
  kind?: RenderTaskKind
  /** 类型展示名（渲染 / 实例导入 / 存档同步 / 删除地图） */
  kindLabel?: string
  /** 展示主体：渲染任务＝地图名，导入任务＝来源实例名 */
  subject?: string
  mapId: string
  mapName: string
  generationId: string | null
  state: RenderTaskState
  phase: RenderPhase | null
  phaseLabel: string | null
  percent: number
  completedTiles: number
  totalTiles: number
  message: string | null
  error: string | null
  logTail: string | null
  /**
   * 引擎结构化诊断：版本体检警告、采集版本与判据、模型来源、实体/地图画统计、贴图改名兜底。
   * 这些行出现在子进程输出的**开头**，会被 logTail 的尾部窗口挤掉，所以后端单独抽出来落库。
   */
  diagnostics: string[]
  canCancel: boolean
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
}

export type RenderTaskKind = 'RENDER' | 'IMPORT' | 'SYNC' | 'DELETE'

export interface PreviewMeta {
  version: string
  originX: number
  originZ: number
  step: number
  width: number
  depth: number
  blockMinX: number
  blockMinZ: number
  blockMaxX: number
  blockMaxZ: number
  regionCount: number
  chunkCount: number
  minSurfaceY: number | null
  maxSurfaceY: number | null
}

export interface Preview {
  ready: boolean
  state: string | null
  percent: number
  done: number
  total: number
  message: string | null
  uploadId: string | null
  dimension: string | null
  error: string | null
  meta: PreviewMeta | null
  /** 后端已算好的「最低渲染高度」建议，未就绪时 null */
  suggestedMinY: number | null
  imageUrl: string | null
  windowSize: number | null
}

export interface EngineStatus {
  engineReady: boolean
  javaExecutable: string | null
  runtimeRoot: string | null
  heapMiB: number
  timeoutMinutes: number
  maxConcurrentTasks: number
  harvestCapable: boolean
  modelsCached: boolean
  modelsFile: string | null
  sidecarEnabled: boolean
  sidecarState: string | null
  sidecarPort: number | null
  sidecarError: string | null
  sidecarRetryInSeconds: number | null
  sidecarLaunchPlan: string[]
  bundledResources: string[]
}

export interface MarkersResponse {
  sets: unknown[]
}

export interface HealthStatus {
  ok: boolean
}

/** GET / POST /admin/maps/{id}/panorama */
export type PanoramaState = 'IDLE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface PanoramaStatus {
  mapId: string
  state: PanoramaState
  percent: number
  message: string
  /** 当前已发布代里是否已有全景图（据此决定是否渲染预览图） */
  generated: boolean
  width: number
  height: number
  yaw: number
  pitch: number
  fov: number
  distance: number
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  logTail: string
}

/** POST /admin/maps/{id}/panorama 请求体（全部可选，缺省用后端默认值） */
export interface PanoramaTriggerPayload {
  width?: number
  height?: number
  yaw?: number
  pitch?: number
  fov?: number
  distance?: number
}

/** GET / POST / DELETE /admin/maps/{id}/shard-worker */
export type ShardWorkerState = 'STOPPED' | 'RUNNING' | 'EXITED'

export interface ShardWorkerStatus {
  mapId: string
  state: ShardWorkerState
  /** 子进程 pid；未运行时为 -1（原生进程号，不是雪花的 Long ID，可按 number 处理） */
  pid: number
  workerId: string | null
  threads: number
  alive: boolean
  /** 共享队列目录：多机指向同一目录即可协作 */
  queueDir: string
  /** 队列能力说明（后端明示「只托管进程、不生产队列」） */
  queueHint: string
  /** worker 直接写入的磁盘产物目录 */
  artifactDir: string
  publishedTo: string | null
  syncedTiles: number
  startedAt: string | null
  exitNote: string | null
  error: string | null
  logTail: string
}

/** POST /admin/maps/{id}/shard-worker 请求体 */
export interface ShardWorkerStartPayload {
  workerId?: string
  threads?: number
  leaseMinutes?: number
  idleMinutes?: number
}

/** 模组包里的一个 jar（解析包内内容用）。 */
export interface ModPackJar {
  name: string
  sizeBytes: number
}

/** 模组包清单：解析 zip 中央目录得到，不做解压。 */
export interface ModPackListing {
  fileId: string
  name: string
  jarCount: number
  totalBytes: number
  jars: ModPackJar[]
  /** 明细被截断（jarCount 仍为全量） */
  truncated: boolean
}
