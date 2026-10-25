import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FederationStats, UnionAuditLog, UnionBan, UnionJoinEvent, UnionMeta, UnionPage, UnionPeer, UnionSiteSettings } from '../types'

export function createUnionApi(sdk: YuDreamPluginSdk) {
  return {
    meta: () => sdk.http.get<UnionMeta>('/peer/meta'),
    federationStats: () => sdk.http.get<FederationStats>('/admin/federation/stats'),
    federationLog: (page: number, pageSize: number, site?: string, name?: string, sortBy = 'recordedAt', order: 'asc' | 'desc' = 'desc') => {
      const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize), sortBy, order })
      if (site) query.set('site', site)
      if (name) query.set('name', name)
      return sdk.http.get<UnionPage<UnionJoinEvent>>(`/admin/federation/log?${query.toString()}`)
    },
    settings: () => sdk.http.get<UnionSiteSettings>('/admin/settings'),
    saveSettings: (body: UnionSiteSettings) =>
      sdk.http.request<UnionSiteSettings>('/admin/settings', { method: 'PUT', data: body }),
    issueEnrollToken: () => sdk.http.post<{ enrollToken: string; expiresAt: number }>('/admin/enroll-tokens', {}),
    pagePeers: (page: number, pageSize: number) =>
      sdk.http.get<UnionPage<UnionPeer>>(`/admin/peers?page=${page}&pageSize=${pageSize}`),
    selfEnrollPeer: (body: { peerOrigin: string; enrollToken: string; note?: string }) =>
      sdk.http.post<{ peerSiteCode?: string; peerSiteName?: string; status?: string; peerSecret?: string }>(
        '/admin/peers/self-enroll', body),
    setPeerStatus: (siteCode: string, status: string) =>
      sdk.http.request<UnionPeer>(`/admin/peers/${encodeURIComponent(siteCode)}/status`, { method: 'POST', data: { status } }),
    refreshPeer: (siteCode: string) =>
      sdk.http.post<UnionPeer>(`/admin/peers/${encodeURIComponent(siteCode)}/refresh`, {}),
    selfTestPeer: (siteCode: string) =>
      sdk.http.post<{ ok: boolean; detail: string }>(`/admin/peers/${encodeURIComponent(siteCode)}/selftest`, {}),
    deletePeer: (siteCode: string) =>
      sdk.http.request(`/admin/peers/${encodeURIComponent(siteCode)}`, { method: 'DELETE' }),
    deleteAudit: (id: string) =>
      sdk.http.request(`/admin/audit/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    clearAudit: () =>
      sdk.http.request<{ cleared: boolean }>('/admin/audit', { method: 'DELETE' }),
    pageBans: (page: number, pageSize: number) =>
      sdk.http.get<UnionPage<UnionBan>>(`/admin/bans?page=${page}&pageSize=${pageSize}`),
    addBan: (body: { name: string; reason: string; evidence?: string }) =>
      sdk.http.post<UnionBan>('/admin/bans', body),
    pullBans: () => sdk.http.post<{ applied: number }>('/admin/bans/pull', {}),
    liftBan: (name: string) =>
      sdk.http.request(`/admin/bans/${encodeURIComponent(name)}`, { method: 'DELETE' }),
    pageAudit: (page: number, pageSize: number, keyword?: string, sortBy = 'at', order: 'asc' | 'desc' = 'desc') => {
      const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize), sortBy, order })
      if (keyword) query.set('keyword', keyword)
      return sdk.http.get<UnionPage<UnionAuditLog>>(`/admin/audit?${query.toString()}`)
    },
  }
}

export type UnionApi = ReturnType<typeof createUnionApi>
