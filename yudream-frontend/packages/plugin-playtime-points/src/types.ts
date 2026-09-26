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
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export interface ServerRule {
  weight: number
  enabled: boolean
}

/** GET/PUT /admin/settings 的设置结构；servers 的 weight 在后端以十进制字符串保存。 */
export interface PointsSettings {
  enabled: boolean
  assetCode: string
  minutesPerPoint: number
  subtractAfk: boolean
  servers: Record<string, { weight: string | null, enabled: boolean }>
}

export interface SaveSettingsPayload {
  enabled: boolean
  assetCode: string
  minutesPerPoint: number
  subtractAfk: boolean
  servers: Record<string, ServerRule>
}

export interface AssetOption {
  code: string
  name: string
  symbol: string
  scale: number
  money: boolean
  enabled: boolean
}

export interface ServerOption {
  id: string
  name: string
  configured: boolean
  weight: string | null
  enabled: boolean
}

export interface AdminOptions {
  settings: PointsSettings
  dependencies: { minecraftServer: boolean, wallet: boolean }
  servers: ServerOption[]
  assets: AssetOption[]
}

export interface ScanResult {
  credited: number
  sessions: number
  message: string
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

export interface MySummary {
  assetCode: string
  totalPoints: string
  totalEffectiveMinutes: number
  sessions: number
  byServer: ServerSummary[]
  walletAvailable: boolean
  assetName: string | null
  assetSymbol: string
  balance: string | null
}
