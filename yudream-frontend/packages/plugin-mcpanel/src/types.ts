/**
 * MC 面板 M1 共享模型（与后端 NodeRes/stats/envelope 契约对齐）。
 *
 * 约定：
 * - 所有 ID 一律 string，禁止 Number(id)。
 * - 时间戳为 epoch 毫秒，后端可能返回 number 或 string，读取统一走 toEpochMs()。
 * - 容量单位 MiB/GiB（1024 基）；CPU 为百分比 0-100。
 * - 前端不持有节点 secret；enroll token 仅在重签响应中出现一次，不落持久缓存。
 * - 无 labels/instanceCount/reservedPorts 字段，不得虚构；portRange 为可选实例端口分配区间。
 */

/** 节点生命周期状态（四态，与后端枚举对齐）。 */
export type McpNodeStatus = 'enrolling' | 'connecting' | 'online' | 'offline'

/**
 * 节点控制信道 TLS 校验模式：
 * - pkix：标准证书链校验（默认）；
 * - pinned：自签指纹钉住，必须提供 pinSha256。
 */
export type McpTlsMode = 'pkix' | 'pinned'

export interface McpNodeContainerStat {
  instanceId: string
  state: string
  cpuPercent?: number
  memUsedMb?: number
}

/** 节点实时快照（SSE event=node.stats 的 payload）。 */
export interface McpNodeStats {
  nodeId?: string
  cpuPercent?: number
  memUsedMb?: number
  memTotalMb?: number
  diskUsedGb?: number
  diskTotalGb?: number
  load?: number
  containers?: McpNodeContainerStat[]
  dockerVersion?: string
  agentVersion?: string
  reportedAt?: number | string | null
}

/** 节点整机历史采样点（后台 30s 采样、已落库）。 */
export interface McpNodeHistorySample {
  at: number | string
  cpuPercent?: number
  memUsedMb?: number
  memTotalMb?: number
}

/**
 * 玩家接入方式：决定实例域名解析记录怎么取值。
 * direct=节点直连地址；manual=自定义解析 IP/主机名；frp=FRP 入口（端口 1:1）；
 * entry=单端口入口（mc-router 按域名转发，A 指向入口）；p2p=启动器打洞（不写公网解析）。
 */
export type McpNodeAccessMode = 'direct' | 'manual' | 'frp' | 'entry' | 'p2p'

/** 节点状态变更（SSE event=node.state 的 payload）。 */
export interface McpNodeStateEvent {
  nodeId?: string
  status?: McpNodeStatus | string
  reason?: string
  at?: number | string | null
}

/** 节点管理视图（NodeRes）。任何节点接口都不得返回 secret 明文。 */
export interface McpNode {
  id: string
  name: string
  /** 授权地址：wss://host[:port]；/control 路径由面板统一追加。 */
  endpoint?: string
  /** SFTP 对管理员的广告地址覆盖（选填）；未配置时面板按 endpoint host 推导。 */
  sftpHost?: string | null
  tlsMode?: McpTlsMode | string
  /**
   * 预批准自签证书指纹（sha256），tlsMode=pinned 时使用；公开哈希非秘密。
   * 未注册节点可留空：注册（enroll）时以节点上报指纹自动登记（TOFU）；
   * 已注册节点必须保留，不能清空。
   */
  pinSha256?: string
  /** 节点注册时上报的证书指纹；TOFU 登记的 pin 即来源于此。 */
  reportedCertSha256?: string
  /** 本地开发模式：仅放宽 loopback 证书校验，仍禁止明文 ws://。 */
  localDevelopment?: boolean
  enabled?: boolean
  status?: McpNodeStatus | string
  /** 面板与节点控制信道当前是否已连接。 */
  connected?: boolean
  /** 面板是否持有该节点密钥；仅 false 时允许重签注册凭据。 */
  hasSecret?: boolean
  agentVersion?: string
  dockerVersion?: string
  reportedHost?: string
  sessionId?: string
  caps?: string[]
  tenantId?: string | null
  remark?: string
  /** 实例对外端口分配区间起始（选填；null/缺省 = 回落默认段）。 */
  portRangeStart?: number | null
  /** 实例对外端口分配区间结束（选填；与 portRangeStart 成对出现）。 */
  portRangeEnd?: number | null
  stats?: McpNodeStats | null
  /**
   * 后台采集的整机曲线（30s 采样、已落库，最近 30 分钟）：页面打开即有历史，
   * 不再依赖「页面开着的时候才开始攒点」。
   */
  history?: McpNodeHistorySample[] | null
  /** 玩家接入方式与解析地址（manual/frp 时有效）。 */
  accessMode?: McpNodeAccessMode | string
  accessHost?: string | null
  accessLabel?: string
  lastSeenAt?: number | string | null
  createdAt?: number | string | null
  updatedAt?: number | string | null
}

/** 创建/更新节点请求。后端对 PUT 缺省/null 字段按「保持现值」处理，因此
 * localDevelopment/remark 等可清空字段必须始终显式传值（空串即清除）。 */
export interface McpNodeSaveRequest {
  name: string
  endpoint: string
  /** SFTP 广告地址覆盖；始终发送，清空覆盖时传 ''。 */
  sftpHost?: string
  /** 玩家接入方式（域名解析取值）；始终发送。 */
  accessMode?: McpNodeAccessMode
  /** manual/frp 的解析地址（IP 或主机名）；direct/p2p 传 '' 清空。 */
  accessHost?: string
  tlsMode: McpTlsMode
  /**
   * tlsMode=pinned 时：新建/未注册节点可传 ''（注册时 TOFU 自动登记），
   * 已注册节点必填；非 pinned 必须传 '' 以清除旧指纹。
   */
  pinSha256?: string
  /** 始终发送 boolean：允许目标地址为 loopback；不放宽任何 TLS 校验。 */
  localDevelopment?: boolean
  /** 始终发送；清空备注时传 ''。 */
  remark?: string
  enabled?: boolean | null
  /**
   * 端口分配范围（1-65535，成对出现）。创建时空 = 默认段；
   * 更新时 null = 保持现值，0 = 清除已设范围回落默认段。
   */
  portRangeStart?: number | null
  portRangeEnd?: number | null
}

/** 重签注册凭据响应：token 仅本次展示，不落任何持久缓存。 */
export interface McpEnrollCredential {
  token: string
  expiresAt?: number | string | null
}

/** 删除节点响应。 */
export interface McpDeleteResult {
  deleted?: boolean
}

/** 后端分页通用结构；total 可能是 number 或字符串化的 long。 */
export interface McpPage<T> {
  records?: T[]
  total?: number | string
  page?: number
  size?: number
}

/** SSE 连接状态机：connecting → open；断开进入 error 并按退避重连；主动取消为 closed。 */
export type McpStreamState = 'connecting' | 'open' | 'closed' | 'error'

/** 管理员维护的 Docker 镜像映射（版本 → 镜像）。 */
export interface McpDockerImage {
  id: string
  kind: string
  mcVersion: string
  label: string
  image: string
  javaHint?: string
  note?: string
  isDefault?: boolean
  enabled?: boolean
  updatedAt?: number | string | null
}

/**
 * 注入制品类型（与后端 PanelSettings.Artifact.kind 对齐）：
 * - plugin：服务端插件（Paper 等，按 MC 版本区分）；
 * - mod：模组（按加载器细分，loaders 必填）。
 */
export type McpInjectArtifactKind = 'plugin' | 'mod'

/** 注入制品矩阵条目（与后端 PanelSettings.Artifact 对齐）。 */
export interface McpInjectArtifact {
  name: string
  kind: McpInjectArtifactKind | string
  /** mod 形态必填：fabric/forge/neoforge/quilt；plugin 形态为空。 */
  loaders?: string[]
  /** 适用 MC 版本下限（含），如 1.20；空 = 不限。 */
  mcMin?: string
  /** 适用 MC 版本上限（含）；空 = 不限。 */
  mcMax?: string
  /** 平台制品库文件 ID（与 url 二选一，优先）。 */
  fileId?: string
  /** 外部下载地址（与 fileId 二选一）。 */
  url?: string
  /** 制品 sha256（上传时由后端计算回显）。 */
  sha256?: string
}

/** 制品上传响应（admin/artifacts）。 */
export interface McpArtifactUploadResult {
  fileId: string
  name: string
  size: number
  sha256: string
}

/** 引导式创建实例请求（在常规实例字段上附加模板/镜像目录选择）。 */
export interface McpInstanceCreateRequest {
  id: string
  nodeId: string
  name: string
  kind: string
  mcVersion?: string | null
  image: string
  command: string[]
  env?: Record<string, string>
  memoryMb: number
  cpuMillis: number
  diskMb: number
  remark?: string | null
  mcServerId?: string | null
  /** 启动成功检测标记：控制台输出包含该内容即判定启动成功；空 = 按类型默认。 */
  startDetect?: string
  /** 服务端模板 key；存在时后端可展开 installer / 启动命令缺省值。 */
  templateKey?: string
  /** 管理员 Docker 镜像映射 id；存在时后端可回填镜像/版本/类型。 */
  dockerImageId?: string
  /** 模板创建时是否要求节点按 installer 自动下载核心。 */
  autoDownloadCore?: boolean
}

/** 代理纳管：单条子服条目（识别结果与保存载荷共用）。 */
export interface McpProxyServerRow {
  name: string
  address: string
  /** 绑定的面板实例 ID；空 = 外部子服（仅记录）。 */
  boundInstanceId?: string
  boundName?: string
  boundState?: string
  external?: boolean
  /** 仅识别响应：address（地址命中）/ name（同名建议）/ carried（沿用已确认绑定）/ none。 */
  matchedInstanceId?: string
  matchedName?: string
  matchType?: string
}

/** 代理纳管组（mcpanel_proxy_groups 文档）。 */
export interface McpProxyGroup {
  proxyInstanceId: string
  proxyName?: string
  kind: 'velocity' | 'bungee' | string
  sourcePath?: string
  servers: McpProxyServerRow[]
  defaultServer?: string
  forwarding?: string
  createdAt?: number | string
  updatedAt?: number | string
}

/** 识别结果（detect，只读）。 */
export interface McpProxyDetectResult {
  detected?: boolean
  reason?: string
  kind?: 'velocity' | 'bungee' | string
  sourcePath?: string
  forwarding?: string
  defaultServer?: string
  servers?: McpProxyServerRow[]
}

/** 实例 DTO 上的父子关系摘要（列表/详情装饰字段）。 */
export interface McpProxyRelationInfo {
  proxyInstanceId: string
  proxyName: string
  serverName: string
}

export interface McpProxyChildInfo {
  serverName: string
  instanceId: string
  instanceName: string
  state: string
}
