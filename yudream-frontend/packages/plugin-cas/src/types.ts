export interface SsoSettings {
  enabled: boolean
  protocol: 'CAS' | 'OIDC' | 'BRIDGE'
  displayName: string
  icon: string
  casBaseUrl: string
  loginPath: string
  validatePath: string
  oidcIssuer: string
  oidcAuthorizePath: string
  oidcTokenPath: string
  oidcUserinfoPath: string
  oidcJwksPath: string
  oidcRegisterPath: string
  clientId: string
  clientSecretConfigured: boolean
  scopes: string
  callbackUrl: string
  /** 登录前是否走本站预热页（先跨站请求拿网关会话 cookie 再跳认证地址）。 */
  loginWarmup: boolean
  /** aTrust 客户端唤起链接（自定义协议）；空=预热页不展示 aTrust 引导。 */
  atrustWakeUrl: string
  /** aTrust 下载/帮助页（http/https）；空=不展示。 */
  atrustGuideUrl: string
  ready: boolean
}

export interface ConnectivityResult {
  ok: boolean
  message: string
}

export interface OidcRegisterResult {
  clientId: string
  clientSecretIssued: boolean
}

/** 访问控制配置（独立于 SsoSettings 存储）：只剩「未绑定则限制使用其他功能」开关。 */
export interface AccessControl {
  requireBinding: boolean
}

/**
 * 该外部账号在本站的绑定查询结果（宿主 SPI 2.29.0 findByExternalIdentity）。
 * available=false 表示宿主不支持查询或查询失败，此时 message 说明原因，页面按「无法查询」降级展示。
 */
export interface ExternalBinding {
  available: boolean
  bound: boolean
  message?: string
  userId?: string
  username?: string
  nickname?: string
  email?: string
  phone?: string
  avatar?: string
  status?: string
}

/**
 * 该学号在「学生档案」插件（yudream-student-info）里的记录（只读）。
 * 学院 / 班级只从这里取；available=false 表示插件未安装或未启用。
 */
export interface StudentArchive {
  available: boolean
  filled: boolean
  message?: string
  studentName?: string
  className?: string
  college?: string
}

/** CAS/OIDC 登录时 upsert 的认证账号档案（学院/班级不在这里，见 StudentArchive）。 */
export interface StudentProfile {
  socialUid: string
  name: string
  email: string
  phone: string
  protocol: string
  rawAttributes: string
  firstSeenAt: number
  lastSeenAt: number
  loginCount: number
  /** 该学号绑定的本站账号（管理端列表/详情附加）。 */
  binding?: ExternalBinding
  /** 该学号在学生档案插件里的记录（管理端列表/详情附加）。 */
  archive?: StudentArchive
}

export interface StudentPage {
  items: StudentProfile[]
  total: number
  page: number
  size: number
  /** 学生档案插件是否可用：false 时学院 / 班级列只显示 —。 */
  archiveAvailable: boolean
}

/** 公开门禁配置：前端全局挂件读取。 */
export interface GateConfig {
  requireBinding: boolean
  providerCode: string
  type: string
  displayName: string
}

export function errorMessage(error: unknown, fallback = '操作失败') {
  if (error && typeof error === 'object') {
    const record = error as { message?: string, data?: { message?: string }, response?: { data?: { message?: string } } }
    return record.response?.data?.message || record.data?.message || record.message || fallback
  }
  return fallback
}
