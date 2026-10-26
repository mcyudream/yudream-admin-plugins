import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Bundle, ModelRow, Page } from '../types'
export function catalogApi(sdk: YuDreamPluginSdk) {
  return {
    list: (page: number, size: number) => sdk.http.get<Page<ModelRow>>(`/catalog?page=${page}&size=${size}`),
    preview: (id: string) => sdk.http.get<Bundle>(`/catalog/${encodeURIComponent(id)}/preview`),
  }
}
