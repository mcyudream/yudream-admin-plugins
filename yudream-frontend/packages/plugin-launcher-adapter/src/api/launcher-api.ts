import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { LauncherPack, LauncherPackDetail, PackForm, PagedResult, YmclChromeAdmin, YmclChromeForm } from '../types'

export function createLauncherApi(sdk: YuDreamPluginSdk) {
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
    packs: (keyword: string, page = 1, size = 10) =>
      sdk.http.get<PagedResult<LauncherPack>>(`/admin/packs${query({ keyword, page, size })}`),
    pack: (packId: string) =>
      sdk.http.get<LauncherPackDetail>(`/admin/packs/${encodeURIComponent(packId)}`),
    createPack: (data: PackForm) =>
      sdk.http.post<LauncherPack>('/admin/packs', data),
    rollback: (packId: string, targetVersionId: string) =>
      sdk.http.post<LauncherPack>(`/admin/packs/${encodeURIComponent(packId)}/rollback`, { targetVersionId }),
    chrome: () => sdk.http.get<YmclChromeAdmin>('/admin/chrome'),
    saveChrome: (data: YmclChromeForm) =>
      sdk.http.request<YmclChromeAdmin>('/admin/chrome', { method: 'PUT', data }),
  }
}
