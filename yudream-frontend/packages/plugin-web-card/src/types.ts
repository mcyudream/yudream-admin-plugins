export type AccessMode = 'PUBLIC_HTTP' | 'CUSTOM_HEADERS'
export type SourceType = 'HTML' | 'JSON' | 'RSS' | 'SITEMAP'
export type TemplateMode = 'STRUCTURED' | 'ADVANCED'
export interface PageResult<T> { records: T[]; total: number }
export interface Option { id: string; name: string; platform?: string; selfId?: string }
export interface Site { id: string; name: string; enabled: boolean; hosts: string[]; accessMode: AccessMode; headerNames: string[]; secretRef?: string; responseType: SourceType; redirectHosts: string[]; defaultTemplateId?: string; createdAt: number; updatedAt: number }
export interface FieldRule { name: string; expression: string; attribute: string; type: string; required: boolean }
export interface ParseRules { siteId: string; detailType: SourceType; fields: FieldRule[]; listExpression: string; listLinkAttribute: string; jsonItemsPath: string; canonicalField: string; contentKeyField: string; detailUrlPattern: string }
export interface SiteRouteRule { id: string; siteId: string; name: string; enabled: boolean; templateId: string; rules: ParseRules; createdAt: number; updatedAt: number }
export interface CardTemplate { id: string; siteId: string; name: string; mode: TemplateMode; draftVersionId?: string; publishedVersionId?: string; createdAt: number; updatedAt: number }
export interface TemplateVersion { id?: string; templateId: string; version: number; parseRules: ParseRules; mode: TemplateMode; structuredLayout: string; html: string; css: string; fixture: Record<string, unknown>; origin: string; summary: string; previewPassed: boolean; createdAt: number }
export interface TemplateDraftPreviewRequest { siteId: string; url: string; version: TemplateVersion; site?: Site; rules?: ParseRules }
export interface TemplateDraftPreviewResult { base64: string; fields: Record<string, unknown>; site: string; finalUrl: string }
export interface GroupBinding { id: string; siteId: string; connectionId: string; platform: string; selfId: string; channelId: string; enabled: boolean; templateVersionId?: string; quietStart: string; quietEnd: string; cooldownSeconds: number; hourlyLimit: number; lastDeliveryAt: number; createdAt: number; updatedAt: number }
export interface CrawlJob { id: string; siteId: string; sourceUrl: string; sourceType: SourceType; enabled: boolean; intervalMinutes: number; initialItemCount: number; nextRunAt: number; initialized: boolean; createdAt: number; updatedAt: number }
export interface DeliveryRecord { id: string; contentId?: string; bindingId?: string; templateVersionId?: string; stage: string; attempts: number; error?: string; nextAttemptAt: number; createdAt: number; updatedAt: number }
export interface AgentSession { id?: string; siteId: string; templateId: string; agentCode: string; messages: { role: string; content: string }[]; createdAt: number; updatedAt: number }
export interface AgentProposal { id: string; sessionId: string; summary: string; operations: { target: string; operation: string; value: unknown }[]; status: string; previewVersionId?: string; createdAt: number; updatedAt: number }
export interface WorkspacePlanSite {
  id?: string
  name: string
  enabled: boolean
  hosts: string[]
  accessMode: AccessMode
  responseType: SourceType
  redirectHosts: string[]
}
export interface WorkspacePlanTemplate {
  id?: string
  name: string
  mode: TemplateMode
  structuredLayout: string
  html: string
  css: string
  fixture: Record<string, unknown>
}
export interface WorkspacePlanBinding {
  connectionId: string
  channelId: string
  enabled: boolean
  quietStart: string
  quietEnd: string
  cooldownSeconds: number
  hourlyLimit: number
}
export interface WorkspacePlanJob {
  sourceUrl: string
  sourceType: SourceType
  enabled: boolean
  intervalMinutes: number
  initialItemCount: number
}
export interface WorkspacePlan {
  summary: string
  site: WorkspacePlanSite
  rules: Omit<ParseRules, 'siteId'> & { siteId?: string }
  template: WorkspacePlanTemplate
  binding: WorkspacePlanBinding | null
  job: WorkspacePlanJob | null
  publish: boolean
}
