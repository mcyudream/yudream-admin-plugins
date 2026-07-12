import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiProvider, AiTool, GroupPolicy, Option } from '../types'
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
  }
}
