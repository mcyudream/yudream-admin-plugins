import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { EconomyRecord, MinecraftServer, MinecraftStatusSnapshot, PageResult, PlayerActivity, SeasonOperation } from '../types'

export function createMinecraftApi(sdk: YuDreamPluginSdk) {
  function query(params: Record<string, string | number | boolean | undefined>) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        search.set(key, String(value))
      }
    })
    const value = search.toString()
    return value ? `?${value}` : ''
  }

  return {
    list: (page = 1, size = 10, refresh = false) => sdk.http.get<PageResult<MinecraftServer>>(`/servers${query({ page, size, refresh })}`),
    adminList: (page = 1, size = 10, refresh = false) => sdk.http.get<PageResult<MinecraftServer>>(`/admin/servers${query({ page, size, refresh })}`),
    detail: (id: string, refresh = false) => sdk.http.get<MinecraftServer>(`/servers/${encodeURIComponent(id)}${query({ refresh })}`),
    adminDetail: (id: string, refresh = false) => sdk.http.get<MinecraftServer>(`/admin/servers/${encodeURIComponent(id)}${query({ refresh })}`),
    save: (data: Record<string, unknown>) => sdk.http.post<MinecraftServer>('/admin/servers', data),
    remove: (id: string) => sdk.http.request(`/admin/servers/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    refreshStatus: (id: string) => sdk.http.post<MinecraftServer>(`/admin/servers/${encodeURIComponent(id)}/status/refresh`),
    statusHistory: (id: string, since?: number, limit?: number) => sdk.http.get<MinecraftStatusSnapshot[]>(`/servers/${encodeURIComponent(id)}/status/history${query({ since, limit })}`),
    economyStatus: () => sdk.http.get<{ walletEnabled: boolean }>('/economy/status'),
    previewSeason: (id: string, data: Record<string, unknown>) => sdk.http.post<SeasonOperation>(`/admin/servers/${encodeURIComponent(id)}/seasons/preview`, data),
    openSeason: (id: string, data: Record<string, unknown>) => sdk.http.post<SeasonOperation>(`/admin/servers/${encodeURIComponent(id)}/seasons/open`, data),
    operations: (id: string, page = 1, size = 10) => sdk.http.get<PageResult<SeasonOperation>>(`/admin/servers/${encodeURIComponent(id)}/operations${query({ page, size })}`),
    rollbackOperation: (operationId: string) => sdk.http.post<SeasonOperation>(`/admin/operations/${encodeURIComponent(operationId)}/rollback`),
    myRecords: (id: string, page = 1, size = 10) => sdk.http.get<PageResult<EconomyRecord>>(`/me/servers/${encodeURIComponent(id)}/records${query({ page, size })}`),
    playerActivities: (id: string, page = 1, size = 10) => sdk.http.get<PageResult<PlayerActivity>>(`/admin/servers/${encodeURIComponent(id)}/players${query({ page, size })}`),
  }
}
