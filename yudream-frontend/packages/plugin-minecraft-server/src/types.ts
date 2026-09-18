export interface MinecraftEndpoint {
  id?: string
  name: string
  host: string
  port?: number | string | null
  edition: 'JAVA' | 'BEDROCK' | string
  primaryLine: boolean
  enabled: boolean
  sort: number
}

export interface MinecraftSeason {
  id?: string
  name: string
  description?: string
  startedAt?: TimeValue
  endedAt?: TimeValue
  current: boolean
  sort: number
  modpackBinding?: ModpackBinding | null
}

export interface ModpackBinding {
  type: 'NONE' | 'VANILLA' | 'MRPACK' | string
  gameVersion?: string | null
  loader?: string | null
  packId?: string | null
  versionId?: string | null
}

export interface MinecraftEndpointStatus {
  endpointId: string
  status: 'ONLINE' | 'OFFLINE' | string
  onlinePlayers: number
  maxPlayers: number
  versionName?: string
  protocolId?: number
  ping?: number
  motd?: string
  errorMessage?: string
  checkedAt: TimeValue
}

export interface MinecraftServerStatus {
  serverId: string
  status: 'ONLINE' | 'OFFLINE' | string
  onlinePlayers: number
  maxPlayers: number
  endpoints: MinecraftEndpointStatus[]
  checkedAt: TimeValue
}

export interface MinecraftStatusSnapshot {
  id: string
  serverId: string
  status: 'ONLINE' | 'OFFLINE' | string
  onlinePlayers: number
  maxPlayers: number
  checkedAt: TimeValue
}

export interface MinecraftServerMap {
  fileId?: string
  originalName?: string
  publicAccess: boolean
  externalUrl?: string
}

/** One downstream server behind a proxy, reported by the bridge running on that proxy. */
export interface MinecraftSubServer {
  name: string
  address: string
  online: number
  sensor: boolean
  defaultServer: boolean
  sort: number
}

/** The proxy's reported server list. Absent for a server that is not a proxy. */
export interface MinecraftTopology {
  proxy: string
  proxyVersion: string
  reportedAt: TimeValue
  reported: boolean
  onlinePlayers: number
  sensorCount: number
  servers: MinecraftSubServer[]
}

export interface MinecraftServer {
  id: string
  name: string
  descriptionMarkdown: string
  enabled: boolean
  sort: number
  endpoints: MinecraftEndpoint[]
  seasons: MinecraftSeason[]
  currentSeason?: MinecraftSeason
  status?: MinecraftServerStatus
  map?: MinecraftServerMap
  topology?: MinecraftTopology
  createdAt: TimeValue
  updatedAt: TimeValue
}

export interface InheritanceRule {
  assetPattern: string
  minAmount: string | number
  maxAmount?: string | number
  inheritRate: string | number
  rangeLabel?: string
}

export interface SeasonAdjustment {
  userId: string
  assetCode: string
  inheritedAmount: string | number
  seasonIncomeAmount: string | number
  seasonTotalAmount: string | number
  realTotalIncomeAmount: string | number
  nextInheritedAmount: string | number
  walletBalanceBefore: string | number
  deltaAmount: string | number
  direction: 'CREDIT' | 'DEBIT' | 'NONE' | string
  ruleLabel: string
  walletTransactionId?: string
  rollbackTransactionId?: string
}

export interface SeasonOperation {
  id: string
  serverId: string
  fromSeasonId?: string
  toSeasonId?: string
  toSeasonName: string
  status: 'PREVIEW' | 'APPLIED' | 'ROLLED_BACK' | string
  rules: InheritanceRule[]
  adjustments: SeasonAdjustment[]
  operatorUserId?: string
  remark?: string
  createdAt: TimeValue
  rolledBackAt?: TimeValue
}

export interface EconomyRecord {
  id: string
  type: string
  source: string
  status: string
  assetCode: string
  amount: string | number
  businessNo?: string
  remark?: string
  createdAt: TimeValue
}

/**
 * 玩家在单个子服上的时长明细。
 *
 * <p>`name` 为 `default` 表示这条记录没有子服维度（单机服，或未按子服上报的旧数据）。
 */
export interface PlayerSubServerActivity {
  name: string
  online: boolean
  afk: boolean
  onlineMillis: number
  afkMillis: number
  currentOnlineSince?: TimeValue
  currentAfkSince?: TimeValue
  lastJoinedAt?: TimeValue
  lastQuitAt?: TimeValue
}

/**
 * 界面直接渲染的子服时长行：在原始明细上带上展示名与已格式化的时长。
 *
 * 保留后端的全部字段，而不只是时长——子服行要能显示该子服自己的在线/挂机状态与进出服时间。
 */
export interface PlayerSubServerDetail {
  name: string
  label: string
  online: boolean
  afk: boolean
  onlineMillis: number
  afkMillis: number
  duration: string
  afkDuration: string
  currentOnlineSince?: TimeValue
  currentAfkSince?: TimeValue
  lastJoinedAt?: TimeValue
  lastQuitAt?: TimeValue
}

export interface PlayerActivity {
  serverId: string
  playerId: string
  playerName: string
  online: boolean
  afk: boolean
  totalOnlineMillis: number
  totalAfkMillis: number
  currentOnlineSince?: TimeValue
  currentAfkSince?: TimeValue
  lastJoinedAt?: TimeValue
  lastQuitAt?: TimeValue
  updatedAt: TimeValue
  /** 按子服拆分的明细；顶层 total* 是这些明细之和。 */
  subServers?: PlayerSubServerActivity[]
}

export interface ServerForm {
  id: string
  name: string
  descriptionMarkdown: string
  enabled: boolean
  sort: number
  endpoints: MinecraftEndpoint[]
  seasons: MinecraftSeason[]
}

export interface SeasonForm {
  name: string
  description: string
  startedAtText: string
  remark: string
  rules: InheritanceRule[]
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export type TimeValue = number | string | number[] | Date | null | undefined
