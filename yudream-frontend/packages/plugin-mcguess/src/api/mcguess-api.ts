import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McguessGameView, McguessMyStats, McguessOverview, McguessPlayerView, McguessSettings, PagedResult } from '../types'

export function createMcguessApi(sdk: YuDreamPluginSdk) {
  function query(params: Record<string, string | number | undefined>) {
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
    overview: () => sdk.http.get<McguessOverview>('/admin/overview'),
    games: (mode: string, status: string, page = 1, size = 10) => sdk.http.get<PagedResult<McguessGameView>>(`/admin/games${query({ mode, status, page, size })}`),
    players: (page = 1, size = 10) => sdk.http.get<PagedResult<McguessPlayerView>>(`/admin/players${query({ page, size })}`),
    settings: () => sdk.http.get<McguessSettings>('/admin/settings'),
    saveSettings: (gameVersion: string) => sdk.http.request<McguessSettings>('/admin/settings', { method: 'PUT', data: { gameVersion } }),
    myStats: () => sdk.http.get<McguessMyStats>('/me/stats'),
  }
}
