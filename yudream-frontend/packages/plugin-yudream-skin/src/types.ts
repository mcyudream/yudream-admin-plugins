export type SkinPage = 'dashboard' | 'players' | 'textures' | 'closet' | 'settings' | 'playerManagement' | 'textureManagement' | 'closetManagement'

export interface SkinSettings {
  maxPlayersPerUser: number
  allowPublicUpload: boolean
  siteNotice?: string
}

export interface SkinSummary {
  users: number
  players: number
  textures: number
  closetItems: number
  options: number
  settings?: SkinSettings
}

export interface SkinMe {
  userId: string
  hostUser?: {
    id: string
    username: string
    nickname?: string
    email?: string
    avatar?: string
  }
  skinUser?: SkinUser
  defaultPlayerName?: string
  permissions: string[]
}

export interface SkinUser {
  id: string
  email: string
  nickname: string
  migratedUid?: string
  createdAt?: number
}

export interface SkinPlayer {
  uuid: string
  ownerId: string
  ownerName?: string
  ownerUsername?: string
  ownerEmail?: string
  name: string
  skinHash?: string
  capeHash?: string
  profileKind?: 'offline' | 'official' | string
  lastModified?: number
}

export interface OfficialBindView {
  officialUuid: string
  officialNameLast: string
  verificationMethod: string
  msRefreshStored: boolean
  status: 'active' | 'unbound' | string
  verifiedAt?: number
}

export interface OfficialBindStatus {
  settingsEnabled: boolean
  bind?: OfficialBindView | null
}

export interface OfficialBindSettings {
  enabled: boolean
  clientId: string
  /** 只写字段：留空表示保持已配置的凭据；后端永不回显。 */
  clientSecret?: string
  /** 服务端是否已配置机密客户端凭据。 */
  clientSecretConfigured?: boolean
  /** 站点实际发送给 Microsoft 的回调地址（精确匹配用），origin 未配置时为空。 */
  redirectUri?: string | null
}

export interface SkinTexture {
  hash: string
  name: string
  type: string
  model: string
  contentType?: string
  size?: number
  uploaderId?: string
  publicAccess?: boolean
  uploadedAt?: number
}

export interface SkinClosetItem {
  id: string
  userId?: string
  textureHash: string
  itemName?: string
  createdAt?: number
}

export interface MigrationReport {
  users: number
  players: number
  textures: number
  closetItems: number
  options: number
  warnings: string[]
}

export interface MigrationLogEntry {
  time: number
  level: string
  message: string
}

export interface MigrationStatus {
  state: 'IDLE' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  running: boolean
  startedAt?: number
  finishedAt?: number
  report?: MigrationReport
  logs: MigrationLogEntry[]
}

export interface SelectOption {
  label: string
  value: string
}
