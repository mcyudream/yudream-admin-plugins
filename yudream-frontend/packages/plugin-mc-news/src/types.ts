export interface Page<T> {
  records: T[]
  total: number
}

/** 新闻动态分页响应：额外携带忽略名单（墓碑）、去重记忆与未送达队列条数。 */
export interface NewsPageView extends Page<NewsArticleView> {
  ignored?: number
  seen?: number
  /** 未送达待补推条数 */
  pending?: number
  /** 开启私信订阅的用户数（手动推送弹窗据此提示影响范围） */
  subscribers?: number
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

/** 清空动态的可选项：默认均为否。 */
export interface ClearNewsOptions {
  /** 是否连轮询缓存（去重记录 + 未送达队列）一并清空 */
  clearCache: boolean
  /** 清空缓存后，下一次轮询是否把源里现存内容重新推送一轮 */
  pushOnNextPoll: boolean
}

export interface ClearNewsResult {
  /** 被清空的动态条数 */
  cleared: number
  cacheCleared: boolean
  pushOnNextPoll: boolean
}

/** 手动推送的可选目标（已按端点去重；mergedCount > 0 表示另有同端点目标被合并到它）。 */
export interface PushTargetOption {
  id: string
  name: string
  type: PushTargetType
  /** global = 管理员全局目标，user = 用户个人 Webhook */
  owner: 'global' | 'user'
  /** 端点描述：群聊名或 Webhook 地址 */
  endpointLabel: string
  /** 因端点相同而被合并到该目标的其它目标数 */
  mergedCount: number
}

export interface PushTargetOptionsView {
  targets: PushTargetOption[]
  /** 开启私信订阅的用户数 */
  subscribers: number
}

/** 手动推送参数：targetIds 为空数组表示全部可用目标。 */
export interface PushNewsOptions {
  targetIds: string[]
  includeSubscribers: boolean
}

/** 手动推送单条动态的结果：ok 表示至少一个目标送达成功。 */
export interface PushNewsResult {
  ok: boolean
  /** 送达成功的目标数 */
  okCount: number
  /** 实际尝试推送的目标数 */
  total: number
  results: PushLogResult[]
}

export type PushState = 'pushed' | 'failed' | 'skipped' | 'deduped' | ''

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

export type LogMode = 'poll' | 'manual' | 'push' | 'test'

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

export interface SourceRuleStat {
  rule: string
  kind: string
  field: string
  exclude: boolean
  hits: number
}

export interface SourceTestView {
  ok: boolean
  /** 关键词模板过滤后收录的条数 */
  count: number
  /** 源返回的原始条数（未过滤） */
  beforeFilter: number
  /** 被排除模板丢弃的条数 */
  excluded: number
  /** 未命中任何收录模板的条数 */
  rejected: number
  titles: { title: string, url: string }[]
  rules: SourceRuleStat[]
  /** 非法模板（运行时已跳过） */
  invalid: string[]
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
