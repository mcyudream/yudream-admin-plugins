import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McInteropEntry, McInteropPage, McInteropPeer, McInteropSettings, InteropSiteDocument } from '../types'

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
    mergedEntries: () =>
      sdk.http.get<Record<string, unknown>>('/admin/entries/merged').then(body => {
        const raw = body as Record<string, any>
        const packs = (Array.isArray(raw.packs) ? raw.packs : []).map(pack => ({
          ...pack,
          game_version: pack.game_version ?? pack.gameVersion,
          loader_version: pack.loader_version ?? pack.loaderVersion,
          updated_at: pack.updated_at ?? pack.updatedAt,
          source: pack.source
            ? {
                ...pack.source,
                site_id: pack.source.site_id ?? pack.source.siteId,
                domain: pack.source.domain,
                stale: pack.source.stale,
              }
            : undefined,
        }))
        return {
          site: raw.site,
          packs,
          peers: raw.peers ?? [],
          updated_at: raw.updated_at ?? raw.updatedAt,
        } as InteropSiteDocument
      }),
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
