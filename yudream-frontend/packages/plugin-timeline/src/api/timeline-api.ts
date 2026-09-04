import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Page, TimelineEventPayload, TimelineEventSummary, TimelineEventView, TimelineStatusFilter } from '../types'

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

// 后端简单列表端点返回 { records: [...] } 包裹
function records<T>(payload: unknown): T[] {
  const body = payload as { records?: T[] } | T[]
  return Array.isArray(body) ? body : (body?.records ?? [])
}

export function createTimelineApi(sdk: YuDreamPluginSdk) {
  const { http } = sdk

  return {
    publicEvents: () => records<TimelineEventSummary>(http.get('/public/events')),
    publicEvent: (eventId: string) => http.get<TimelineEventView>(`/public/events/${encodeURIComponent(eventId)}`),
    adminEvents: (keyword: string, status: TimelineStatusFilter, page: number, size: number) =>
      http.get<Page<TimelineEventSummary>>(`/admin/events${query({ keyword, status, page, size })}`),
    adminEvent: (eventId: string) => http.get<TimelineEventView>(`/admin/events/${encodeURIComponent(eventId)}`),
    createEvent: (data: TimelineEventPayload) => http.post<TimelineEventView>('/admin/events', data),
    updateEvent: (eventId: string, data: TimelineEventPayload) =>
      http.request<TimelineEventView>(`/admin/events/${encodeURIComponent(eventId)}`, { method: 'PUT', data }),
    setEventPublished: (eventId: string, published: boolean) =>
      http.post<TimelineEventView>(`/admin/events/${encodeURIComponent(eventId)}/publish`, { published }),
    deleteEvent: (eventId: string) =>
      http.request<{ deleted: boolean }>(`/admin/events/${encodeURIComponent(eventId)}`, { method: 'DELETE' }),
  }
}

export type TimelineApi = ReturnType<typeof createTimelineApi>
