import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  AiProviderOption,
  AutomationPolicy,
  AutomationPolicyOverride,
  MediaJob,
  MediaJobTestRequest,
  Option,
  PageResult,
} from '../types'

export function createQqbotAutomationApi(sdk: YuDreamPluginSdk) {
  return {
    defaultPolicy: (connectionId: string) => sdk.http.get<AutomationPolicy>(`/admin/default-policy?connectionId=${encodeURIComponent(connectionId)}`),
    saveDefaultPolicy: (policy: AutomationPolicy) => sdk.http.request<AutomationPolicy>('/admin/default-policy', { method: 'PUT', data: policy }),
    groupOverrides: (connectionId: string, page: number, size: number) => sdk.http.get<PageResult<AutomationPolicyOverride>>(`/admin/group-overrides?connectionId=${encodeURIComponent(connectionId)}&page=${page}&size=${size}`),
    groupOverride: (connectionId: string, channelId: string) => sdk.http.get<AutomationPolicyOverride | null>(`/admin/group-overrides/${encodeURIComponent(channelId)}?connectionId=${encodeURIComponent(connectionId)}`),
    saveGroupOverride: (override: AutomationPolicyOverride) => sdk.http.request<AutomationPolicyOverride>('/admin/group-overrides', { method: 'PUT', data: override }),
    deleteGroupOverride: (connectionId: string, channelId: string) => sdk.http.request<{ deleted: boolean }>(`/admin/group-overrides/${encodeURIComponent(channelId)}?connectionId=${encodeURIComponent(connectionId)}`, { method: 'DELETE' }),
    connections: () => sdk.http.get<Option[]>('/admin/options/connections'),
    groups: (connectionId: string) => sdk.http.get<Option[]>(`/admin/options/groups?connectionId=${encodeURIComponent(connectionId)}`),
    aiOptions: () => sdk.http.get<AiProviderOption[]>('/admin/options/ai'),
    mediaJob: (id: string) => sdk.http.get<MediaJob | null>(`/admin/media-jobs/${encodeURIComponent(id)}`),
    mediaJobs: (page: number, size: number) => sdk.http.get<PageResult<MediaJob>>(`/admin/media-jobs?page=${page}&size=${size}`),
    clearMediaJobs: () => sdk.http.request<{ deleted: number }>('/admin/media-jobs', { method: 'DELETE' }),
    startMediaTest: (request: MediaJobTestRequest) => sdk.http.request<{ id: string; trigger: string }>('/admin/media-jobs/test', { method: 'POST', data: request }),
  }
}
