import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Bundle, ModelRow, Page } from '../types'
export function adminApi(sdk: YuDreamPluginSdk) {
  return {
    list: (page: number, size: number) => sdk.http.get<Page<ModelRow>>(`/admin/models?page=${page}&size=${size}`),
    preview: (id: string) => sdk.http.get<Bundle>(`/admin/models/${encodeURIComponent(id)}/preview`),
    remove: (id: string) => sdk.http.request(`/admin/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    manage: (id: string, enabled: boolean, serverEnabled: boolean) => sdk.http.request<ModelRow>(`/admin/models/${encodeURIComponent(id)}`, { method: 'PUT', data: { enabled, serverEnabled } }),
  }
}
