import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  AiProviderOption,
  AutomationPolicy,
  AutomationPolicyOverride,
  MediaJob,
  MediaJobTestRequest,
  MediaStorageSettings,
  Option,
  PageResult,
  UserOption,
} from '../types'

type MessagingCatalog = {
  connections: () => Promise<Option[]>
  groups: (connectionId: string) => Promise<Option[]>
}

type AiCatalog = {
  providers: () => Promise<AiProviderOption[]>
}

function messagingCatalog(sdk: YuDreamPluginSdk): MessagingCatalog {
  const client = (sdk as YuDreamPluginSdk & { messaging?: MessagingCatalog }).messaging
  if (!client) {
    return {
      connections: async () => [],
      groups: async () => [],
    }
  }
  return client
}

function aiCatalog(sdk: YuDreamPluginSdk): AiCatalog {
  const client = (sdk as YuDreamPluginSdk & { ai?: AiCatalog }).ai
  if (!client) {
    return { providers: async () => [] }
  }
  return client
}

export function createQqbotAutomationApi(sdk: YuDreamPluginSdk) {
  const messaging = messagingCatalog(sdk)
  const ai = aiCatalog(sdk)
  return {
    defaultPolicy: (connectionId: string) => sdk.http.get<AutomationPolicy>(`/admin/default-policy?connectionId=${encodeURIComponent(connectionId)}`),
    saveDefaultPolicy: (policy: AutomationPolicy) => sdk.http.request<AutomationPolicy>('/admin/default-policy', { method: 'PUT', data: policy }),
    groupOverrides: (connectionId: string, page: number, size: number) => sdk.http.get<PageResult<AutomationPolicyOverride>>(`/admin/group-overrides?connectionId=${encodeURIComponent(connectionId)}&page=${page}&size=${size}`),
    groupOverride: (connectionId: string, channelId: string) => sdk.http.get<AutomationPolicyOverride | null>(`/admin/group-overrides/${encodeURIComponent(channelId)}?connectionId=${encodeURIComponent(connectionId)}`),
    saveGroupOverride: (override: AutomationPolicyOverride) => sdk.http.request<AutomationPolicyOverride>('/admin/group-overrides', { method: 'PUT', data: override }),
    deleteGroupOverride: (connectionId: string, channelId: string) => sdk.http.request<{ deleted: boolean }>(`/admin/group-overrides/${encodeURIComponent(channelId)}?connectionId=${encodeURIComponent(connectionId)}`, { method: 'DELETE' }),
    connections: () => messaging.connections(),
    groups: (connectionId: string) => connectionId ? messaging.groups(connectionId) : Promise.resolve([]),
    aiOptions: () => ai.providers(),
    mediaJob: (id: string) => sdk.http.get<MediaJob | null>(`/admin/media-jobs/${encodeURIComponent(id)}`),
    mediaJobs: (page: number, size: number) => sdk.http.get<PageResult<MediaJob>>(`/admin/media-jobs?page=${page}&size=${size}`),
    clearMediaJobs: () => sdk.http.request<{ deleted: number }>('/admin/media-jobs', { method: 'DELETE' }),
    startMediaTest: (request: MediaJobTestRequest) => sdk.http.request<{ id: string; trigger: string }>('/admin/media-jobs/test', { method: 'POST', data: request }),
    mediaSettings: () => sdk.http.get<MediaStorageSettings>('/admin/media-settings'),
    saveMediaSettings: (settings: MediaStorageSettings) => sdk.http.request<MediaStorageSettings>('/admin/media-settings', { method: 'PUT', data: settings }),
    userOptions: (keyword: string, page: number, size: number) => sdk.http.get<PageResult<UserOption>>(`/admin/options/users?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`),
  }
}
