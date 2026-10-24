/** AI 模板办公领域类型（与后端 record 对应；长 ID 一律 string）。 */

export interface SchemaFieldDef {
  key: string
  label: string
  type: string
  required: boolean
  options: string[]
}

export interface OfficeSchema {
  id: string
  code: string
  name: string
  role: 'profile' | 'entity'
  builtin: boolean
  fields: SchemaFieldDef[]
  updatedAt: number
}

export interface CredentialKind {
  code: string
  label: string
}

export interface DocTypeMapping {
  var: string
  source: string
  transform: string
  itemMapping: Record<string, string>
}

export interface OfficeDocType {
  id: string
  code: string
  name: string
  stage: string
  wordTemplateCode: string
  schemaCodes: string[]
  mappings: DocTypeMapping[]
  fileNamePattern: string
  enabled: boolean
  updatedAt: number
}

export interface OfficePrompt {
  id: string
  code: string
  name: string
  system: string
  user: string
  updatedAt: number
}

export interface OfficeCategory {
  id: string
  direction: 'income' | 'expense'
  code: string
  name: string
  fields: string[]
  schemaCode: string
  sort: number
  enabled: boolean
}

export interface PackageItem {
  docTypeCode: string
  target: 'PER_ACTIVITY' | 'PER_MONTH'
  subDir: string
}

export interface OfficePackage {
  id: string
  code: string
  name: string
  dirPattern: string
  items: PackageItem[]
  enabled: boolean
  updatedAt: number
}

export interface FinanceAttachment {
  fileKey: string
  filename: string
  contentType: string
  size: number
  kind: string
}

export interface FinanceRecord {
  id: string
  direction: 'income' | 'expense'
  categoryCode: string
  subCategory: string
  title: string
  amountCents: number
  quantity: number
  unitPriceCents: number
  buyerId: string
  buyerName: string
  occurredAt: number
  purpose: string
  fundSource: string
  data: Record<string, unknown>
  attachments: FinanceAttachment[]
  status: 'pending' | 'accepted' | 'rejected'
  reviewedBy: string
  reviewedAt: number
  reviewNote: string
  settlementBatchId: string
  createdBy: string
  createdAt: number
}

export interface SettlementBatch {
  id: string
  period: string
  status: 'open' | 'closed'
  incomeTotal: number
  expenseTotal: number
  balanceCarryIn: number
  balanceCarryOut: number
  closedAt: number
  closedBy: string
}

export interface SettlementOverview {
  period: string
  status: string
  incomeTotal: number
  expenseTotal: number
  balanceCarryIn: number
  balanceCarryOut: number
  pendingCount: number
  closedAt: number
  closedBy: string
}

export interface DocJob {
  id: string
  docTypeCode: string
  sourceType: 'ACTIVITY' | 'RECORD' | 'MONTH'
  sourceId: string
  period: string
  status: 'draft' | 'generating' | 'drafted' | 'final' | 'failed'
  draft: Record<string, string>
  aiRuns: string[]
  resultFileKey: string
  resultFilename: string
  error: string
  createdBy: string
  createdAt: number
  updatedAt: number
}

export interface PreviewRow {
  var: string
  source: string
  value: unknown
  missing: boolean
  ai: boolean
}

export interface PreviewResult {
  rows: PreviewRow[]
  context: Record<string, unknown>
}

export interface PrecheckRow {
  docTypeCode: string
  docTypeName: string
  targetId: string
  targetLabel: string
  sourceType: string
  period: string
  ready: boolean
  jobId: string
  filename: string
  missing: boolean
}

export interface WordTemplateOption {
  code: string
  name: string
  originalFilename: string
}

export interface AiProviderOption {
  code: string
  name: string
  models: { code: string, name: string }[]
}

export interface PresetInfo {
  code: string
  name: string
  description: string
  wordTemplates: { code: string, name: string, note: string }[]
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export interface OfficeSettings {
  profileSchemaCode: string
  profile: Record<string, string>
  financeCycle: string
  currency: string
  styleGuide: string
  aiProviderCode: string
  aiModelCode: string
  wordImageEmbed: boolean
  credentialKinds: CredentialKind[]
  activePresetCode: string
  updatedAt: number
}

export interface DatasetRecord {
  id: string
  schemaCode: string
  data: Record<string, unknown>
  attachments: FinanceAttachment[]
  createdBy: string
  createdAt: number
  updatedAt: number
}

export function moneyText(cents: number | string | undefined | null): string {
  const value = Number(cents ?? 0)
  const yuan = Math.trunc(value / 100)
  const remainder = Math.abs(value % 100)
  return remainder === 0 ? String(yuan) : `${yuan}.${String(remainder).padStart(2, '0')}`
}

export function formatTime(epoch: number | undefined | null): string {
  if (!epoch) {
    return '—'
  }
  return new Date(epoch).toLocaleString('zh-CN', { hour12: false })
}

export function jobStatusText(status: string): string {
  switch (status) {
    case 'draft':
      return '待起草'
    case 'generating':
      return '生成中'
    case 'drafted':
      return '草稿待定稿'
    case 'final':
      return '已定稿'
    case 'failed':
      return '失败'
    default:
      return status
  }
}
