export interface McInteropSettings {
  siteId?: string
  siteName?: string
  description?: string
  domain?: string
  /** union = 身份来自联合认证插件（只读）；local = 本页维护 */
  identitySource?: 'union' | 'local' | string
}

export interface McInteropEntry {
  id: string
  name?: string
  summary?: string | null
  revision?: number | null
  gameVersion?: string
  loader?: string
  loaderVersion?: string | null
  artifactFormat?: string | null
  artifactUrl?: string | null
  artifactSize?: number | null
  sha1?: string | null
  sha512?: string | null
  distSystem?: string | null
  distPackId?: string | null
  distVersionId?: string | null
  serverAddress?: string
  serverPort?: number | null
  published?: boolean
  updatedAt?: number | string
}

export interface McInteropPeer {
  id?: string
  siteId?: string
  name?: string | null
  description?: string | null
  domain?: string | null
  manifestUrl?: string
  addedAt?: number | string
  /** false = 已屏蔽：声明保留（出口 peers 列表仍在），但互采不拉取不采合 */
  enabled?: boolean
}

/** 手动拉取的逐站结果（宿主 Long→String 定制下数字可能为字符串）。 */
export interface McInteropPeerPullResult {
  siteId?: string
  enabled?: boolean
  ok?: boolean
  packs?: number | string
  error?: string
  note?: string
}

export interface McInteropPeerPull {
  elapsedMs?: number | string
  packs?: number | string
  updatedAt?: string
  results?: McInteropPeerPullResult[]
}

export interface McInteropPage<T> {
  records?: T[]
  total?: number | string
}

/** 管理表单专用形状：纯 string/undefined，避免 null 进入输入组件模型。 */
export interface McInteropEntryForm {
  id: string
  name: string
  summary: string
  revision: number
  gameVersion: string
  loader: string
  loaderVersion: string
  artifactFormat: string
  artifactUrl: string
  /** 0 = 未填（保存时剥离） */
  artifactSize: number
  sha1: string
  sha512: string
  serverAddress: string
  /** 0 = 不指定端口（保存时剥离） */
  serverPort: number
  published: boolean
}

export interface McInteropPeerForm {
  siteId: string
  name: string
  description: string
  domain: string
  manifestUrl: string
}

// ----- mc-interop v1 出口条目（合并视图/互采用） -----
export interface InteropPackEntry {
  id: string
  name?: string
  summary?: string
  revision?: number
  game_version?: string
  loader?: string
  loader_version?: string
  artifact?: { format?: string; url: string; size?: number; sha1?: string | null; sha512?: string | null } | null
  server?: { address: string; port?: number }
  source?: { site_id: string; domain?: string; stale?: boolean }
}

export interface InteropSiteDocument {
  site?: { id: string; name?: string; description?: string; domain?: string }
  packs: InteropPackEntry[]
  peers: { site_id: string; name?: string; manifest_url?: string }[]
  updated_at?: string
}
