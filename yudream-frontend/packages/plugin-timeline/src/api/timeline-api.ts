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

/** 简单列表端点后端统一返回 {records:[...]}，在此拆包；裸数组响应也兼容。 */
function records<T>(promise: Promise<unknown>): Promise<T[]> {
  return promise.then((body) => {
    if (Array.isArray(body)) {
      return body as T[]
    }
    const wrapped = body as { records?: T[] } | null | undefined
    return Array.isArray(wrapped?.records) ? wrapped.records : []
  })
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
