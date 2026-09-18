/** 后端 payload 字段与 YAP 协议一致（snake_case）；长 ID/时间戳一律 string。 */

export interface DomainConfig {
  name?: string | null
  description?: string | null
  logo_url?: string | null
  updatedAt?: string | null
}

export interface AuthMethod {
  type: string
  endpoint?: string
  authorize_url?: string
  token_url?: string
  client_id?: string
  scopes?: string[]
  pkce?: string
}

export interface Capabilities {
  protocol_version: number
  adapter_version: string
  domain: { name: string, description?: string | null, logo_url?: string | null }
  capabilities: string[]
  auth: { required: boolean, methods: AuthMethod[] }
}

export interface NavigationItem {
  id: string
  /** native | page | group（纯目录） */
  type: string
  route?: string
  page_id?: string
  title: string
  icon?: string
  sort?: number
  required_permission?: string | null
  children?: NavigationItem[]
}

/** 导航配置节点：page 引用注册表；directory 为纯目录（无路由）。 */
export interface NavigationConfigItem {
  kind?: 'page' | 'directory'
  /** page 节点必填 */
  pageId?: string
  /** directory 节点必填（持久化 id） */
  id?: string
  /** 展示标题覆盖；directory 必填 */
  title?: string
  /** 展示图标覆盖（Remix 图标名） */
  icon?: string
  enabled: boolean
  sort?: number
  children?: NavigationConfigItem[]
}

export interface NavigationPageInfo {
  pageId: string
  source: 'native' | 'provider' | 'custom'
  title: string
  route?: string | null
  icon?: string | null
  requiredPermission?: string | null
}

export interface NavigationConfig {
  configured: boolean
  items: NavigationConfigItem[]
  pages?: NavigationPageInfo[]
  updatedAt?: string | null
}

/** 适配器自定义页面（YmclCustomPagesController）：渲染器限 bundle 类。 */
export interface CustomPageBundle {
  id: string
  version: string
  entry: string
  sha256: string
  url?: string
  /** module 页面可申请的热页宿主能力（data.fetch/action.execute/open-url/theme.read） */
  permissions?: string[]
}

export interface CustomPageDoc {
  id: string
  title: string
  renderer: 'extension' | 'module'
  icon?: string
  requiredPermission?: string
  params?: Record<string, unknown>
  bundle: CustomPageBundle
  updatedAt?: string
}

export interface PageDescriptor {
  id: string
  renderer: string
  title: string
  data_source?: string
  permissions?: string[]
  params?: Record<string, unknown>
  bundle?: Record<string, unknown> | null
}

export interface DataSourceDescriptor {
  id: string
  schema_version?: number
  cache_ttl?: number
}

export interface ManifestPayload {
  protocol_version: number
  navigation: NavigationItem[]
  pages: PageDescriptor[]
  data_sources: DataSourceDescriptor[]
  actions: { allow?: string[] }
  home?: Record<string, unknown>
}

export interface PackVersionSummary {
  version: string
  channel?: string
  releasedAt?: string
}

export interface PackDoc {
  packId: string
  name?: string
  description?: string
  icon?: string
  defaultChannel?: string
  versions?: PackVersionSummary[]
  updatedAt?: string
}

export interface PackMetaForm {
  name: string
  description: string
  icon: string
  defaultChannel: string
}

export type CasPreviewKind = 'text' | 'mod' | 'image' | 'binary'

export interface CasFilePreview {
  sha512: string
  path: string
  filename: string
  size: number
  kind: CasPreviewKind
  downloadUrl: string
  contentType?: string
  content?: string
  truncated?: boolean
  metaFiles?: string[]
  meta?: Record<string, unknown>
  message?: string
}

export interface ManifestFileEntry {
  path: string
  sha512: string
  size: number
  policy?: string
}

export interface PackManifest {
  format_version?: number
  pack_id?: string
  version?: string
  parent?: string | null
  channel?: string
  files?: ManifestFileEntry[]
}

export interface PackVersionDoc {
  packId: string
  version: string
  channel?: string
  releasedAt?: string
  manifest: PackManifest
}

export interface ServerBinding {
  packId?: string
  mcVersion?: string | null
  channel?: string
  pinnedVersion?: string | null
  updatePolicy?: string
  serverId?: string
  updatedAt?: string
}

export interface ServerView {
  serverId?: string
  name?: string
  mcAddress?: string
  status?: string | Record<string, unknown>
  currentSeason?: Record<string, unknown> | null
  binding?: ServerBinding
}

export interface BundleDoc {
  bundleId: string
  version: string
  sha256: string
  size: number
  uploadedAt?: string
}

export interface HomeCard {
  title?: string
  type?: string
  [key: string]: unknown
}

export interface HomeConfig {
  schemaVersion?: number
  locked?: boolean
  cards?: HomeCard[]
  updatedAt?: string
}

/**
 * YAP §6.9 ThemeProfile：随 manifest.theme 下发，字段与启动器侧解析一致
 * （camelCase）。空节点 = 该组不覆盖，成员回退个人偏好。
 */
export interface ThemeProfile {
  schemaVersion?: number
  mode?: { default?: 'dark' | 'light' | 'oled' | 'system' }
  accentColor?: { value?: string }
  background?: { url?: string, blur?: number, opacity?: number }
  window?: { transparent?: boolean, opacity?: number, blur?: boolean }
  advancedRendering?: { value?: boolean }
  pageTransitions?: { value?: boolean }
  cssVars?: Record<string, string>
  updatedAt?: string
  [key: string]: unknown
}

export interface ThemeConfigView {
  configured: boolean
  theme: ThemeProfile
  updatedAt?: string | null
}

export interface YmclEvent {
  type?: string
  [key: string]: unknown
}
