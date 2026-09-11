export interface Page<T> {
  records: T[]
  total: number
}

/** 新闻动态分页响应：额外携带忽略名单（墓碑）数量。 */
export interface NewsPageView extends Page<NewsArticleView> {
  ignored?: number
}

export type NewsSourceType = 'mcnet' | 'zendesk'

export interface NewsSourceView {
  id: string
  name: string
  type: NewsSourceType
  url: string
  keywords: string[]
  enabled: boolean
  builtin: boolean
  createdAtLabel: string
}

export interface NewsSourcePayload {
  name: string
  type: NewsSourceType
  url: string
  keywords: string[]
  enabled: boolean
}

export type PushTargetType = 'messaging' | 'webhook'

export interface WebhookHeader {
  key: string
  value: string
}

export interface PushTargetView {
  id: string
  name: string
  type: PushTargetType
  enabled: boolean
  connectionId: string
  channelId: string
  channelName: string
  webhookUrl: string
  headers: WebhookHeader[]
  markdown: boolean
}

export interface PushTargetPayload {
  name: string
  type: PushTargetType
  enabled?: boolean
  connectionId?: string
  channelId?: string
  channelName?: string
  webhookUrl?: string
  headers?: WebhookHeader[]
  markdown?: boolean
}

export type PushState = 'pushed' | 'failed' | 'skipped' | ''

export interface NewsArticleView {
  id: string
  sourceId: string
  sourceName: string
  title: string
  summary: string
  aiSummary: string
  category: string
  url: string
  imageUrl: string
  publishedAt: number
  publishedAtLabel: string
  discoveredAt: number
  discoveredAtLabel: string
  pushedAt: number
  pushedAtLabel: string
  pushState: PushState
}

export type LogMode = 'poll' | 'manual' | 'test'

export interface PushLogResult {
  targetId: string
  targetName: string
  targetType: string
  ok: boolean
  error: string
}

export interface PushLogView {
  id: string
  mode: LogMode
  title: string
  url: string
  sourceName: string
  total: number
  okCount: number
  createdAt: number
  createdAtLabel: string
  results: PushLogResult[]
}

export interface McNewsSettingsView {
  enabled: boolean
  pollIntervalMinutes: number
  cacheSize: number
  pushOnFirstPoll: boolean
  aiEnabled: boolean
  aiProviderCode: string
  aiModelCode: string
  aiMaxItems: number
  aiContentMaxChars: number
  aiSystemPrompt: string
  messageTemplate: string
  lastPollAt: number
  lastPollAtLabel: string
  lastPollSummary: string
  defaultTemplate: string
  defaultAiPrompt: string
  polling: boolean
}

export interface TemplateVariable {
  name: string
  description: string
}

export interface TemplateMeta {
  variables: TemplateVariable[]
  defaultTemplate: string
  defaultAiPrompt: string
  preview: string
}

export interface ConnectionOption {
  id: string
  name: string
  platform: string
}

export interface GroupOption {
  id: string
  name: string
}

export interface PollStatusView {
  enabled: boolean
  polling: boolean
  lastPollAt: number
  lastPollAtLabel: string
  lastPollSummary: string
  pollIntervalMinutes: number
  nextPollAt: number
  nextPollAtLabel: string
  /** 距下次轮询的秒数；-1 表示已暂停，0 表示即将执行 */
  nextPollInSeconds: number
}

export interface SourceTestView {
  ok: boolean
  count: number
  titles: { title: string, url: string }[]
  elapsedMs: number
}

export interface MySubscriptionView {
  directEnabled: boolean
  webhooks: PushTargetView[]
  maxWebhooks: number
}

export interface TestResultView {
  ok: boolean
  error: string
}
