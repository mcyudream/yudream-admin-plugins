import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PagedResult, PonyGameView, PonyMyStats, PonyOverview, PonyPlayerView } from '../types'

export function createPonyApi(sdk: YuDreamPluginSdk) {
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
    overview: () => sdk.http.get<PonyOverview>('/admin/overview'),
    games: (status: string, page = 1, size = 10) => sdk.http.get<PagedResult<PonyGameView>>(`/admin/games${query({ status, page, size })}`),
    players: (page = 1, size = 10) => sdk.http.get<PagedResult<PonyPlayerView>>(`/admin/players${query({ page, size })}`),
    myStats: () => sdk.http.get<PonyMyStats>('/me/stats'),
  }
}
