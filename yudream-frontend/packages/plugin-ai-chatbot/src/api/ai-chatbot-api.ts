import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiProvider, AiTool, GroupPolicy, MemoryProfile, MemoryProfilePage, Option } from '../types'
export function createAiChatbotApi(sdk: YuDreamPluginSdk) {
  return {
    policies: () => sdk.http.get<GroupPolicy[]>('/admin/policies'),
    policy: (connectionId: string, channelId: string) => sdk.http.get<GroupPolicy>(`/admin/policy?connectionId=${encodeURIComponent(connectionId)}&channelId=${encodeURIComponent(channelId)}`),
    save: (policy: GroupPolicy) => sdk.http.request<GroupPolicy>('/admin/policy', { method: 'PUT', data: policy }),
    saveBatch: (connectionIds: string[], channelIds: string[], policy: GroupPolicy) => sdk.http.request<GroupPolicy[]>('/admin/policies/batch', { method: 'PUT', data: { connectionIds, channelIds, policy } }),
    connections: () => sdk.http.get<Option[]>('/admin/options/connections'),
    groups: (connectionId: string) => sdk.http.get<Option[]>(`/admin/options/groups?connectionId=${encodeURIComponent(connectionId)}`),
    tools: () => sdk.http.get<AiTool[]>('/admin/options/tools'),
    providers: () => sdk.http.get<AiProvider[]>('/admin/options/providers'),
    memoryProfiles: (page: number, size: number) => sdk.http.get<MemoryProfilePage>(`/admin/memory-profiles?page=${page}&size=${size}`),
    setMemoryProfileEnabled: (id: string, enabled: boolean) => sdk.http.request<MemoryProfile>(`/admin/memory-profile/enabled?id=${encodeURIComponent(id)}&enabled=${enabled}`, { method: 'POST' }),
    deleteMemoryProfile: (id: string) => sdk.http.request('/admin/memory-profile?id=' + encodeURIComponent(id), { method: 'DELETE' }),
  }
}
