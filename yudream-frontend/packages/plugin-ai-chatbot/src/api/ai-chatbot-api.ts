import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ActivityEventPage, ActivityFilters, ActivityHeatmapCell, ActivityOverview, ActivityTimelinePoint, ActivityUserSummary, AiAgent, AiProviderOption, GroupPolicy, MemoryProfile, MemoryProfilePage, MemoryProfileUpdate, Option, ProfileObservation } from '../types'

function query(filters: ActivityFilters) {
  const values = new URLSearchParams()
  if (filters.from != null) values.set('from', String(filters.from))
  if (filters.to != null) values.set('to', String(filters.to))
  if (filters.connectionId) values.set('connectionId', filters.connectionId)
  if (filters.channelId) values.set('channelId', filters.channelId)
  if (filters.type) values.set('type', filters.type)
  if (filters.user) values.set('user', filters.user)
  if (filters.bucket) values.set('bucket', filters.bucket)
  if (filters.timezone) values.set('timezone', filters.timezone)
  const text = values.toString()
  return text ? `?${text}` : ''
}

export function createAiChatbotApi(sdk: YuDreamPluginSdk) {
  return {
    policies: () => sdk.http.get<GroupPolicy[]>('/admin/policies'),
    policy: (connectionId: string, channelId: string) => sdk.http.get<GroupPolicy>(`/admin/policy?connectionId=${encodeURIComponent(connectionId)}&channelId=${encodeURIComponent(channelId)}`),
    save: (policy: GroupPolicy) => sdk.http.request<GroupPolicy>('/admin/policy', { method: 'PUT', data: policy }),
    saveBatch: (connectionIds: string[], channelIds: string[], policy: GroupPolicy) => sdk.http.request<GroupPolicy[]>('/admin/policies/batch', { method: 'PUT', data: { connectionIds, channelIds, policy } }),
    connections: () => sdk.http.get<Option[]>('/admin/options/connections'),
    groups: (connectionId: string) => sdk.http.get<Option[]>(`/admin/options/groups?connectionId=${encodeURIComponent(connectionId)}`),
    agents: () => sdk.http.get<AiAgent[]>('/admin/options/agents'),
    aiProviders: () => sdk.http.get<AiProviderOption[]>('/admin/options/ai-providers'),
    memoryProfiles: (page: number, size: number) => sdk.http.get<MemoryProfilePage>(`/admin/memory-profiles?page=${page}&size=${size}`),
    memoryProfile: (id: string) => sdk.http.get<MemoryProfile>('/admin/memory-profile?id=' + encodeURIComponent(id)),
    saveMemoryProfile: (profile: MemoryProfileUpdate) => sdk.http.request<MemoryProfile>('/admin/memory-profile', { method: 'PUT', data: profile }),
    setMemoryProfileEnabled: (id: string, enabled: boolean) => sdk.http.request<MemoryProfile>(`/admin/memory-profile/enabled?id=${encodeURIComponent(id)}&enabled=${enabled}`, { method: 'POST' }),
    deleteMemoryProfile: (id: string) => sdk.http.request('/admin/memory-profile?id=' + encodeURIComponent(id), { method: 'DELETE' }),
    analyzeMemoryProfile: (id: string) => sdk.http.post<MemoryProfile>(`/admin/memory-profile/analyze?id=${encodeURIComponent(id)}`),
    profileObservations: (id: string) => sdk.http.get<ProfileObservation[]>(`/admin/memory-profile/observations?id=${encodeURIComponent(id)}`),
    activityOverview: (filters: ActivityFilters) => sdk.http.get<ActivityOverview>('/admin/statistics/overview' + query(filters)),
    activityTimeline: (filters: ActivityFilters) => sdk.http.get<ActivityTimelinePoint[]>('/admin/statistics/timeline' + query(filters)),
    activityHeatmap: (filters: ActivityFilters) => sdk.http.get<ActivityHeatmapCell[]>('/admin/statistics/heatmap' + query(filters)),
    activityUsers: (filters: ActivityFilters) => sdk.http.get<ActivityUserSummary[]>('/admin/statistics/users' + query(filters)),
    activityEvents: (filters: ActivityFilters, page: number, size: number) => sdk.http.get<ActivityEventPage>(`/admin/statistics/events${query(filters)}${query(filters) ? '&' : '?'}page=${page}&size=${size}`),
  }
}
