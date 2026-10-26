import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Bundle, ModelRow, Page, Player } from '../types'
export function userApi(sdk: YuDreamPluginSdk) {
  return {
    list: (page: number, size: number) => sdk.http.get<Page<ModelRow>>(`/me/models?page=${page}&size=${size}`),
    preview: (id: string) => sdk.http.get<Bundle>(`/me/models/${encodeURIComponent(id)}/preview`),
    upload: (data: { name: string; base64: string }, id?: string) => sdk.http.post<ModelRow>(id ? `/me/models/${encodeURIComponent(id)}/versions` : '/me/models', data),
    rename: (id: string, name: string) => sdk.http.request<ModelRow>(`/me/models/${encodeURIComponent(id)}`, { method: 'PUT', data: { name } }),
    remove: (id: string) => sdk.http.request(`/me/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    players: () => sdk.http.get<{ records: Player[]; bridgeConfigured: boolean }>('/me/players'),
    equip: (uuid: string, modelId: string, texture: string) => sdk.http.request(`/me/players/${encodeURIComponent(uuid)}/outfit`, { method: 'PUT', data: { modelId, texture } }),
  }
}
