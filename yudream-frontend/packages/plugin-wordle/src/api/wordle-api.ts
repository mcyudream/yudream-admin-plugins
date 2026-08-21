import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PagedResult, WordEntryForm, WordEntryView, WordleGameView, WordleMyStats, WordleOverview, WordlePlayerView } from '../types'

export function createWordleApi(sdk: YuDreamPluginSdk) {
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
    overview: () => sdk.http.get<WordleOverview>('/admin/overview'),
    words: (mode: string, keyword: string, page = 1, size = 10) => sdk.http.get<PagedResult<WordEntryView>>(`/admin/words${query({ mode, keyword, page, size })}`),
    createWord: (data: { mode: string, word: string, hint?: string }) => sdk.http.post<WordEntryView>('/admin/words', data),
    updateWord: (form: WordEntryForm) => sdk.http.request<WordEntryView>(`/admin/words/${encodeURIComponent(form.id)}`, { method: 'PUT', data: { hint: form.hint, enabled: form.enabled } }),
    deleteWord: (id: string) => sdk.http.request(`/admin/words/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    games: (status: string, page = 1, size = 10) => sdk.http.get<PagedResult<WordleGameView>>(`/admin/games${query({ status, page, size })}`),
    players: (page = 1, size = 10) => sdk.http.get<PagedResult<WordlePlayerView>>(`/admin/players${query({ page, size })}`),
    myStats: () => sdk.http.get<WordleMyStats>('/me/stats'),
  }
}
