import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Bundle, ModelRow, ModelSettings, Page } from '../types'
export function adminApi(sdk: YuDreamPluginSdk) {
  return {
    list: (page: number, size: number, reviewStatus = 'ALL') => sdk.http.get<Page<ModelRow>>(`/admin/models?page=${page}&size=${size}&reviewStatus=${encodeURIComponent(reviewStatus)}`),
    settings: () => sdk.http.get<ModelSettings>('/admin/settings'),
    saveSettings: (data: ModelSettings) => sdk.http.request<ModelSettings>('/admin/settings', { method: 'PUT', data }),
    review: (id: string, hash: string, approved: boolean, note: string) => sdk.http.post<ModelRow>(`/admin/models/${encodeURIComponent(id)}/review`, { hash, approved, note }),
    preview: (id: string) => sdk.http.get<Bundle>(`/admin/models/${encodeURIComponent(id)}/preview`),
    remove: (id: string) => sdk.http.request(`/admin/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    manage: (id: string, enabled: boolean, serverEnabled: boolean) => sdk.http.request<ModelRow>(`/admin/models/${encodeURIComponent(id)}`, { method: 'PUT', data: { enabled, serverEnabled } }),
  }
}
