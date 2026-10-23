import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McInteropEntry, McInteropPage, McInteropPeer, McInteropSettings } from '../types'

export function createMcInteropApi(sdk: YuDreamPluginSdk) {
  return {
    settings: () => sdk.http.get<McInteropSettings>('/admin/settings'),
    saveSettings: (body: McInteropSettings) =>
      sdk.http.request<McInteropSettings>('/admin/settings', { method: 'PUT', data: body }),
    entries: (page: number, pageSize: number, keyword?: string, published?: boolean) => {
      const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
      if (keyword) query.set('keyword', keyword)
      if (published !== undefined) query.set('published', String(published))
      return sdk.http.get<McInteropPage<McInteropEntry>>(`/admin/entries?${query.toString()}`)
    },
    saveEntry: (body: McInteropEntry) => sdk.http.post<McInteropEntry>('/admin/entries', body),
    publish: (id: string, published: boolean) =>
      sdk.http.request<McInteropEntry>(`/admin/entries/${encodeURIComponent(id)}/publish`, {
        method: 'POST',
        data: { published },
      }),
    deleteEntry: (id: string) =>
      sdk.http.request(`/admin/entries/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    importFromAdapter: () =>
      sdk.http.request<{ imported: number; skipped: string[] }>('/admin/entries/import-adapter', {
        method: 'POST',
        data: {},
      }),

    peers: () => sdk.http.get<McInteropPage<McInteropPeer>>('/admin/peers'),
    savePeer: (body: McInteropPeer) => sdk.http.post<McInteropPage<McInteropPeer>>('/admin/peers', body),
    deletePeer: (siteId: string) =>
      sdk.http.request(`/admin/peers/${encodeURIComponent(siteId)}`, { method: 'DELETE' }),
  }
}

export type McInteropApi = ReturnType<typeof createMcInteropApi>
