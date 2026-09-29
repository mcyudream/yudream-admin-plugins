/** 群组服下某个子服的时长与积分拆分（金额为十进制字符串，时长为毫秒）。 */
export interface SubSettlementRecord {
  subServer: string
  onlineMillis: number
  afkMillis: number
  effectiveMillis: number
  effectiveMinutes: number
  weight: string
  points: string
  /** false 表示该子服当期不参与结算，points 恒为 0，但时长仍然如实展示。 */
  enabled: boolean
}

/** 玩家每次退出服务器后的结算流水（金额为十进制字符串，时间戳为毫秒）。 */
export interface SettlementRecord {
  id: string
  serverId: string
  serverName: string
  playerId: string
  playerName: string
  userId: string
  windowEnd: number | null
  onlineMillis: number
  afkMillis: number
  effectiveMillis: number
  effectiveMinutes: number
  weight: string
  points: string
  credited: string
  assetCode: string
  createdAt: number
  /** 子服明细；整服口径（单机服、提供方旧版本）为空数组。 */
  subServers: SubSettlementRecord[]
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export interface ServerRule {
  weight: number
  enabled: boolean
}

/** 打卡积分的计算方式：FIXED = 每次固定积分（默认），HOURLY = 按时薪折算。 */
export type CheckInRewardMode = 'FIXED' | 'HOURLY'

/** GET/PUT /admin/settings 的设置结构；weight 在后端以十进制字符串保存。 */
export interface PointsSettings {
  enabled: boolean
  assetCode: string
  minutesPerPoint: number
  subtractAfk: boolean
  servers: Record<string, { weight: string | null, enabled: boolean }>
  /** 子服规则，键为 `serverId::subServer`。 */
  subServers: Record<string, { weight: string | null, enabled: boolean }>
  /** 打卡积分总开关（project-progress 软依赖），默认 false。 */
  checkInRewardEnabled: boolean
  /** 固定金额模式下每次打卡的积分数，十进制字符串（默认 '1'）。 */
  checkInRewardPoints: string
  /** 是否额外注册验收通过实时回调（默认 false，关闭时只靠定时拉取）。 */
  checkInRewardRealtime: boolean
  /**
   * 按项目 ID 的费率覆盖（值为十进制字符串），语义随 `checkInRewardMode` 变化：
   * FIXED 是每次金额，HOURLY 是每小时积分；未配置的项目继承全局。
   */
  checkInRewardProjectPoints: Record<string, string>
  /** 计算方式：FIXED 每次固定积分（默认，旧设置文档读出来也是它）/ HOURLY 按时薪折算。 */
  checkInRewardMode: CheckInRewardMode | string
  /** 按时薪折算时每小时的积分数，十进制字符串（默认 '1'）。 */
  checkInHourlyPoints: string
  /**
   * 「没有时长的打卡」（图片/文件/定位等非 MC 打卡）每次发放的积分，十进制字符串（默认 '0'，即不发）。
   * 只在按时薪模式下作为时长为 0 时的回退生效；固定模式下所有打卡都按 `checkInRewardPoints` 发放。
   * 它是**全局值**，不参与 `checkInRewardProjectPoints` 项目覆盖（旧设置文档缺该字段时读成 '0'）。
   */
  checkInFixedPoints: string
}

export interface SaveSettingsPayload {
  enabled: boolean
  assetCode: string
  minutesPerPoint: number
  subtractAfk: boolean
  servers: Record<string, ServerRule>
  subServers: Record<string, ServerRule>
  checkInRewardEnabled: boolean
  checkInRewardPoints: string
  checkInRewardRealtime: boolean
  checkInRewardProjectPoints: Record<string, string>
  checkInRewardMode: CheckInRewardMode
  checkInHourlyPoints: string
  checkInFixedPoints: string
}

export interface AssetOption {
  code: string
  name: string
  symbol: string
  scale: number
  money: boolean
  enabled: boolean
}

/** 该服已知的子服拓扑，来自代理端上报；单机服为空数组。 */
export interface ServerSubOption {
  name: string
  defaultServer: boolean
  online: number
  sensor: boolean
}

export interface ServerOption {
  id: string
  name: string
  configured: boolean
  weight: string | null
  enabled: boolean
  subServers: ServerSubOption[]
}

/** 项目下拉选项，来自 project-progress 的 projects()；provider 未安装时为空数组。 */
export interface ProjectOption {
  id: string
  name: string
  enabled: boolean
}

/** 打卡积分运行时状态（设置页提示用）。 */
export interface CheckInRewardsStatus {
  enabled: boolean
  realtimeRegistered: boolean
  providerAvailable: boolean
}

export interface AdminOptions {
  settings: PointsSettings
  dependencies: { minecraftServer: boolean, wallet: boolean, projectProgress: boolean }
  servers: ServerOption[]
  assets: AssetOption[]
  projects: ProjectOption[]
  checkInRewardsStatus: CheckInRewardsStatus
}

/** 打卡积分发放来源：PULL = 定时拉取，REALTIME = 验收通过实时回调。 */
export type CheckInRewardSource = 'PULL' | 'REALTIME'

/** 一条「项目打卡验收通过 → 积分」的发放流水；金额为十进制字符串。 */
export interface CheckInRewardRecord {
  checkInId: string
  detailId: string
  detailTitle: string
  projectId: string
  projectName: string
  userId: string
  points: string
  credit: string
  source: CheckInRewardSource | string
  acceptedAt: number
  createdAt: number
  /** 本次生效的计算方式：FIXED 每次固定积分 / HOURLY 按时薪折算；旧流水读出来是 FIXED。 */
  mode: CheckInRewardMode | string
  /** 本次依据的有效在线毫秒数；非 Minecraft 打卡、证据缺失或旧数据为 0。 */
  effectiveMillis: number
  /**
   * 本次实际生效的费率：固定模式是每次金额，按时薪模式是每小时积分，
   * 非时长打卡按固定积分发放时是那笔全局固定积分。
   */
  rate: string
  /**
   * 正常按时薪/固定金额发放时为空；「非时长打卡（图片/文件/定位）按固定积分发放」时是这句中文说明；
   * 折算不足最小入账单位或非时长打卡积分为 0 而未发放时，是未发放的中文原因。
   */
  note: string
}

/** 用户端打卡积分汇总（随 /me/summary 返回）。 */
export interface CheckInRewardsSummary {
  enabled: boolean
  realtime: boolean
  providerAvailable: boolean
  totalPoints: string
  count: number
  recent: CheckInRewardRecord[]
}

export interface ScanResult {
  credited: number
  sessions: number
  message: string
  /** 同一轮手动触发里打卡积分拉取的结果；后端新版本才返回。 */
  checkInRewards?: { credited: number, scanned: number, retried: number, message: string }
}

export interface LastScanInfo {
  lastScanAt: number | null
  message: string
}

export interface ServerSummary {
  serverId: string
  serverName: string
  points: string
  effectiveMinutes: number
  sessions: number
}

/** 某个子服上的累计明细（用户端汇总）。 */
export interface SubServerSummary {
  serverId: string
  serverName: string
  subServer: string
  points: string
  effectiveMinutes: number
  sessions: number
}

export interface MySummary {
  assetCode: string
  totalPoints: string
  totalEffectiveMinutes: number
  sessions: number
  byServer: ServerSummary[]
  bySubServer: SubServerSummary[]
  walletAvailable: boolean
  assetName: string | null
  assetSymbol: string
  balance: string | null
  /** 打卡积分合计与最近记录。 */
  checkInRewards: CheckInRewardsSummary
}
