export interface AuthlibStatus {
  apiRoot?: string
  textureBaseUrl?: string
  serverName?: string
  publicKey?: string
  enabled?: boolean
  accountSource?: string
  skinPluginEnabled?: boolean
  dependencies?: Record<string, unknown>
  [key: string]: unknown
}

export interface AuthlibEndpoint {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  path: string
  note: string
}

export interface AuthlibDisplaySettings {
  /** 生效中的展示名（自定义 > 站点描述 > 站点名称 > Minecraft）。 */
  serverName?: string
  /** 管理员自定义值；空串表示未自定义。 */
  serverNameOverride?: string
}

export interface SigningKeyInfo {
  publicKeyPem: string
  fingerprint: string
  algorithm: string
}
