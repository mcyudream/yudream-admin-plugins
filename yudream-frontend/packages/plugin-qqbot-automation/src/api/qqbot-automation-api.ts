import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AutomationPolicy, MediaJob, Option, PageResult } from '../types'
export function createQqbotAutomationApi(sdk: YuDreamPluginSdk) {
  return {
    policies: () => sdk.http.get<AutomationPolicy[]>('/admin/policies'),
    save: (policy: AutomationPolicy) => sdk.http.request<AutomationPolicy>('/admin/policy', { method: 'PUT', data: policy }),
    connections: () => sdk.http.get<Option[]>('/admin/options/connections'),
    groups: (connectionId: string) => sdk.http.get<Option[]>(`/admin/options/groups?connectionId=${encodeURIComponent(connectionId)}`),
    mediaJobs: (page: number, size: number) => sdk.http.get<PageResult<MediaJob>>(`/admin/media-jobs?page=${page}&size=${size}`),
  }
}
