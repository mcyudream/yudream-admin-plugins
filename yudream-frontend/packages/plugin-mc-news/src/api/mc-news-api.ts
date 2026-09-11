import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  ConnectionOption,
  GroupOption,
  McNewsSettingsView,
  MySubscriptionView,
  NewsArticleView,
  NewsPageView,
  NewsSourcePayload,
  NewsSourceView,
  Page,
  PollStatusView,
  PushLogView,
  PushTargetPayload,
  PushTargetView,
  SourceTestView,
  TemplateMeta,
  TestResultView,
  WebhookHeader,
} from '../types'

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

/** 简单列表端点后端统一返回 {records:[...]}，在此拆包；裸数组响应也兼容。 */
async function records<T>(promise: Promise<unknown>): Promise<T[]> {
  const body = await promise
  if (Array.isArray(body)) {
    return body as T[]
  }
  const wrapped = body as { records?: T[] } | null | undefined
  return Array.isArray(wrapped?.records) ? wrapped.records : []
}

export function createMcNewsApi(sdk: YuDreamPluginSdk) {
  const { http } = sdk

  return {
    // 设置
    settings: () => http.get<McNewsSettingsView>('/admin/settings'),
    saveSettings: (payload: Partial<McNewsSettingsView>) =>
      http.request<McNewsSettingsView>('/admin/settings', { method: 'PUT', data: payload }),
    pollStatus: () => http.get<PollStatusView>('/admin/poll/status'),
    triggerPoll: () => http.post<{ started: boolean }>('/admin/poll', {}),

    // 新闻源
    sources: () => records<NewsSourceView>(http.get('/admin/sources')),
    testSource: (id: string) => http.post<SourceTestView>(`/admin/sources/${encodeURIComponent(id)}/test`, {}),
    createSource: (payload: NewsSourcePayload) => http.post<NewsSourceView>('/admin/sources', payload),
    updateSource: (id: string, payload: Partial<NewsSourcePayload>) =>
      http.request<NewsSourceView>(`/admin/sources/${encodeURIComponent(id)}`, { method: 'PUT', data: payload }),
    deleteSource: (id: string) =>
      http.request<{ deleted: boolean }>(`/admin/sources/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    // 推送目标
    targets: () => records<PushTargetView>(http.get('/admin/targets')),
    createTarget: (payload: PushTargetPayload) => http.post<PushTargetView>('/admin/targets', payload),
    updateTarget: (id: string, payload: Partial<PushTargetPayload>) =>
      http.request<PushTargetView>(`/admin/targets/${encodeURIComponent(id)}`, { method: 'PUT', data: payload }),
    deleteTarget: (id: string) =>
      http.request<{ deleted: boolean }>(`/admin/targets/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    testTarget: (id: string) => http.post<TestResultView>(`/admin/targets/${encodeURIComponent(id)}/test`, {}),

    // 新闻动态与推送记录
    news: (page: number, size: number, sourceId: string, keyword: string) =>
      http.get<NewsPageView>(`/admin/news${query({ page, size, sourceId, keyword })}`),
    deleteNews: (id: string) =>
      http.request<{ deleted: boolean }>(`/admin/news/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    clearNews: () => http.post<{ cleared: number }>('/admin/news/clear', {}),
    clearNewsTombstones: () => http.post<{ cleared: number }>('/admin/news/tombstones/clear', {}),
    logs: (page: number, size: number) => http.get<Page<PushLogView>>(`/admin/logs${query({ page, size })}`),

    // 选项与模板
    connections: () => records<ConnectionOption>(http.get('/admin/options/connections')),
    groups: (connectionId: string) => records<GroupOption>(http.get(`/admin/options/groups${query({ connectionId })}`)),
    templateVariables: () => http.get<TemplateMeta>('/admin/template/variables'),
    previewTemplate: (template: string) => http.post<{ preview: string }>('/admin/template/preview', { template }),

    // 用户订阅
    mySubscription: () => http.get<MySubscriptionView>('/me/subscription'),
    setDirectEnabled: (enabled: boolean) =>
      http.request<{ directEnabled: boolean }>('/me/subscription/direct', { method: 'PUT', data: { enabled } }),
    testMyDirect: () => http.post<TestResultView>('/me/subscription/direct/test', {}),
    createMyWebhook: (payload: { name: string, url: string, headers: WebhookHeader[], enabled: boolean }) =>
      http.post<PushTargetView>('/me/webhooks', payload),
    updateMyWebhook: (id: string, payload: { name?: string, url?: string, headers?: WebhookHeader[], enabled?: boolean }) =>
      http.request<PushTargetView>(`/me/webhooks/${encodeURIComponent(id)}`, { method: 'PUT', data: payload }),
    deleteMyWebhook: (id: string) =>
      http.request<{ deleted: boolean }>(`/me/webhooks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    testMyWebhook: (id: string) => http.post<TestResultView>(`/me/webhooks/${encodeURIComponent(id)}/test`, {}),
  }
}

export type McNewsApi = ReturnType<typeof createMcNewsApi>
