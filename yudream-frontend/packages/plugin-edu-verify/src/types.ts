export type VerificationChannel = 'EMAIL' | 'CHSI' | 'MANUAL' | 'CARSI'
export type VerificationStatus = 'PENDING' | 'PENDING_MAIL' | 'PASSED' | 'REJECTED' | 'EXPIRED' | 'REVOKED'

export interface ChannelInfo {
  code: VerificationChannel
  name: string
  description: string
  enabled: boolean
  tutorialMarkdown?: string | null
}

export interface PublicSettings {
  emailEnabled: boolean
  chsiEnabled: boolean
  manualEnabled: boolean
  codeTtlMinutes: number
  codeResendSeconds: number
  validityDays: number
  chsiMailConfirmationEnabled?: boolean
  chsiMailWaitMinutes?: number
}

export interface PublicMethods {
  pluginCode: string
  methodCode: string
  channels: ChannelInfo[]
  settings: PublicSettings
}

export interface MaterialItem {
  objectKey?: string
  filename?: string
  contentType?: string
  kind?: string
  size?: number | string
  previewUrl?: string
  thumbnailUrl?: string
  previewMode?: 'KKFILE' | 'DIRECT' | 'NONE' | string
  previewMessage?: string
}

export interface VerificationRecord {
  id: string
  email?: string
  userId?: string | null
  channel: VerificationChannel | string
  channelName?: string
  status: VerificationStatus | string
  statusName?: string
  realName?: string | null
  schoolName?: string | null
  note?: string | null
  vcode?: string | null
  reason?: string | null
  decidedBy?: string | null
  submittedAt?: number | string
  decidedAt?: number | string
  expiresAt?: number | string
  createdAt?: number | string
  updatedAt?: number | string
  materialCount?: number
  materials?: MaterialItem[]
}

export interface StatusPayload {
  passed: boolean
  eduDomain?: boolean
  pending?: boolean
  pendingMail?: boolean
  records: VerificationRecord[]
}

export interface EmailSendResult {
  sent: boolean
  email: string
  ttlMinutes: number
  resendSeconds: number
}

export interface ChsiVerifyResult {
  status: 'PASSED' | 'DEGRADED' | 'PENDING_MAIL' | string
  verification?: VerificationRecord
  message?: string
  waitMinutes?: number
}

export interface AdminPage {
  records: VerificationRecord[]
  total: number
  page: number
  size: number
}

export interface DomainRecord {
  domain: string
  chineseName?: string | null
  englishName?: string | null
  enabled: boolean
  source?: string
  createdAt?: number | string
  updatedAt?: number | string
}

export interface NotifyGroupTarget {
  connectionId: string
  groupId: string
}

export interface VerifySettings {
  emailEnabled: boolean
  chsiEnabled: boolean
  manualEnabled: boolean
  codeTtlMinutes: number
  codeResendSeconds: number
  codeDailyLimit: number
  validityDays: number
  retentionDays: number
  chsiDailyLimit: number
  chsiReportUrlTemplate?: string | null
  chsiSelectors?: Record<string, string> | null
  emailTutorialMarkdown: string
  chsiTutorialMarkdown: string
  manualTutorialMarkdown: string
  manualNotifyEnabled: boolean
  manualNotifyGroups: NotifyGroupTarget[]
  manualNotifyTemplate: string
  chsiMailConfirmationEnabled: boolean
  chsiMailboxId: string
  chsiAllowedFromDomains: string[]
  chsiMailKeywords: string[]
  chsiMailWaitMinutes: number
}

export interface ManualMaterialPayload {
  fileId: string
  filename: string
  contentType: string
  kind: string
}

export const MATERIAL_KIND_OPTIONS = [
  { label: '学信网报告', value: 'CHSI_REPORT' },
  { label: '学生证', value: 'STUDENT_CARD' },
  { label: '校园卡', value: 'CAMPUS_CARD' },
  { label: '录取通知书', value: 'ADMISSION' },
  { label: '毕业证', value: 'DIPLOMA' },
  { label: '其他', value: 'OTHER' },
]

export function errorMessage(error: unknown, fallback = '操作失败') {
  if (error && typeof error === 'object') {
    const data = error as { response?: { data?: { message?: string } }, message?: string }
    return data.response?.data?.message || data.message || fallback
  }
  return fallback
}

export function formatTime(value: number | string | undefined | null) {
  if (value == null || value === '' || value === '0') {
    return '-'
  }
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return '-'
  }
  const timestamp = numeric < 10000000000 ? numeric * 1000 : numeric
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false })
}

export function statusVariant(status: string | undefined): 'default' | 'secondary' | 'outline' | 'destructive' {
  switch ((status || '').toUpperCase()) {
    case 'PASSED':
      return 'default'
    case 'PENDING':
    case 'PENDING_MAIL':
      return 'secondary'
    case 'REJECTED':
    case 'REVOKED':
      return 'destructive'
    default:
      return 'outline'
  }
}

export function statusLabel(status: string | undefined, fallback?: string) {
  switch ((status || '').toUpperCase()) {
    case 'PASSED':
      return '已通过'
    case 'PENDING':
      return '待审核'
    case 'PENDING_MAIL':
      return '等待学信网邮件'
    case 'REJECTED':
      return '已驳回'
    case 'EXPIRED':
      return '已过期'
    case 'REVOKED':
      return '已撤销'
    default:
      return fallback || status || '-'
  }
}

export function channelLabel(channel: string | undefined, fallback?: string) {
  switch ((channel || '').toUpperCase()) {
    case 'EMAIL':
      return '教育邮箱'
    case 'CHSI':
      return '学信网'
    case 'MANUAL':
      return '人工审核'
    case 'CARSI':
      return 'CARSI'
    default:
      return fallback || channel || '-'
  }
}

export function queryText(value: string | null | undefined | Array<string | null | undefined>) {
  if (Array.isArray(value)) {
    return value.find(item => item != null && item !== '') || ''
  }
  return value || ''
}

export function kindLabel(kind: string | undefined) {
  return MATERIAL_KIND_OPTIONS.find(item => item.value === kind)?.label || kind || '其他'
}

export function formatSize(value: number | string | undefined | null) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return ''
  }
  if (numeric < 1024) {
    return `${numeric} B`
  }
  if (numeric < 1024 * 1024) {
    return `${(numeric / 1024).toFixed(1)} KB`
  }
  return `${(numeric / (1024 * 1024)).toFixed(1)} MB`
}

export function isImageMaterial(item: MaterialItem) {
  const type = (item.contentType || '').toLowerCase()
  const name = (item.filename || '').toLowerCase()
  return type.startsWith('image/') || /\.(png|jpe?g|gif|webp|bmp)$/i.test(name)
}
