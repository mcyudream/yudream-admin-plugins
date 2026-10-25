export interface UnionSiteSettings {
  siteCode?: string
  siteName?: string
  namePrefix?: string
  origin?: string
  keyFingerprint?: string
  features?: string[]
  auditRetentionDays?: number
  joinRetentionDays?: number
}

export interface UnionMeta {
  unionVersion?: string
  site?: { code?: string, name?: string, origin?: string }
  endpoints?: { apiRoot?: string }
  textureBaseUrl?: string
  signaturePublickey?: string
  keyFingerprint?: string
  features?: string[]
  bans?: { cursor?: string }
  enrollEnabled?: boolean
}

export interface UnionPeer {
  id: string
  siteName?: string
  origin?: string
  apiRoot?: string
  textureBaseUrl?: string
  keyFingerprint?: string
  backupKeyFingerprint?: string | null
  tlsCertSha256?: string
  status?: 'trusted' | 'blocked' | string
  addedBy?: string
  addedAt?: number
  note?: string
  namePrefix?: string
  banCursor?: string
}

export interface UnionBan {
  seq: string
  name?: string
  nameLower?: string
  reason?: string
  sourceSite?: string
  subjectSite?: string
  evidence?: string | null
  createdBy?: string
  createdAt?: number
  expiresAt?: number | null
  status?: 'active' | 'lifted' | string
  liftedBy?: string | null
  liftedAt?: number | null
}

export interface UnionAuditLog {
  id?: string
  actorType?: string
  actor?: string
  action?: string
  target?: string
  detail?: string
  at?: number | string
}

export interface UnionJoinEvent {
  id: string
  direction: 'inbound' | 'outbound' | string
  siteCode: string
  profileId: string
  profileName: string
  serverId: string
  issuerKeyId?: string
  recordedAt: number | string
}

export interface FederationStats {
  inboundTotal?: number
  outboundTotal?: number
  today?: number
  last7?: number
  last30?: number
  distinctPlayers?: number
  bySite?: { siteCode: string, count: number }[]
}

export interface UnionPage<T> {
  records?: T[]
  total?: number
}
