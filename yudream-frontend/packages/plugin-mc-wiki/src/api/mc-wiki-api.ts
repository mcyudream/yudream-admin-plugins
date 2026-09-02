import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { WikiItemDetail, WikiItemTextures, WikiJob, WikiMeta, WikiPage, WikiRecipe, WikiRenderMeta, WikiVersionRow, WikiItem } from '../types'

function query(params: Record<string, string | number | undefined>) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params)) {
    if (item !== undefined && item !== '') {
      value.set(key, String(item))
    }
  }
  const text = value.toString()
  return text ? `?${text}` : ''
}

const id = (value: string) => encodeURIComponent(value)

export function createMcWikiApi(sdk: YuDreamPluginSdk) {
  return {
    // 公开 wiki（version 缺省时服务端使用当前默认已发布版本）
    meta: () => sdk.http.get<WikiMeta>('/public/meta'),
    publicItems: (keyword = '', page = 1, size = 24, version = '') => sdk.http.get<WikiPage<WikiItem>>(`/public/items${query({ keyword, page, size, version })}`),
    publicRecipes: (keyword = '', page = 1, size = 24, version = '') => sdk.http.get<WikiPage<WikiRecipe>>(`/public/recipes${query({ keyword, page, size, version })}`),
    itemDetail: (itemId: string, version = '') => sdk.http.get<WikiItemDetail>(`/public/items/${id(itemId)}${query({ version })}`),
    itemTextures: (itemId: string, version = '') => sdk.http.get<WikiItemTextures>(`/public/items/${id(itemId)}/textures${query({ version })}`),
    iconUrl: (itemId: string, size?: number, version = '', renderGen = '') => `${sdk.http.url('/public/icon')}?id=${id(itemId)}${size ? `&size=${size}` : ''}${version ? `&version=${id(version)}` : ''}${renderGen ? `&v=${id(renderGen)}` : ''}`,
    /** 原始贴图地址（遗留版本数据），version 缺省时服务端使用已发布版本，size 自定义像素尺寸 */
    textureUrl: (texturePath: string, size?: number, version = '') => {
      const slash = texturePath.indexOf('/')
      const params = new URLSearchParams({ kind: texturePath.slice(0, slash), path: texturePath.slice(slash + 1) })
      if (size) params.set('size', String(size))
      if (version) params.set('version', version)
      return `${sdk.http.url('/public/assets/file')}?${params.toString()}`
    },
    // 管理端：版本
    versions: (page = 1, size = 20, keyword = '', type = '', status = '') => sdk.http.get<WikiPage<WikiVersionRow>>(`/admin/versions${query({ page, size, keyword, type, status })}`),
    refreshVersions: () => sdk.http.post<{ records: unknown[] }>('/admin/versions/refresh', {}),
    importVersion: (version: string) => sdk.http.post<{ jobId: string, streamId: string }>(`/admin/versions/${id(version)}/import`, {}),
    publishVersion: (version: string) => sdk.http.post<{ published: boolean }>(`/admin/versions/${id(version)}/publish`, {}),
    unpublishVersion: (version: string) => sdk.http.post<{ published: boolean }>(`/admin/versions/${id(version)}/unpublish`, {}),
    deleteVersionData: (version: string) => sdk.http.request<{ items: number, recipes: number, textures: number, files: number }>(`/admin/versions/${id(version)}/data`, { method: 'DELETE' }),
    // 管理端：共享渲染资产
    renders: () => sdk.http.get<WikiRenderMeta>('/admin/renders'),
    updateRenders: () => sdk.http.post<{ jobId: string, streamId: string }>('/admin/renders/update', {}),
    // 管理端：导入任务
    jobs: (page = 1, size = 20) => sdk.http.get<WikiPage<WikiJob>>(`/admin/jobs${query({ page, size })}`),
    job: (jobId: string) => sdk.http.get<WikiJob>(`/admin/jobs/${id(jobId)}`),
    pauseJob: (jobId: string) => sdk.http.post<WikiJob>(`/admin/jobs/${id(jobId)}/pause`, {}),
    resumeJob: (jobId: string) => sdk.http.post<WikiJob>(`/admin/jobs/${id(jobId)}/resume`, {}),
    cancelJob: (jobId: string) => sdk.http.post<WikiJob>(`/admin/jobs/${id(jobId)}/cancel`, {}),
    deleteJob: (jobId: string) => sdk.http.request<{ deleted: boolean }>(`/admin/jobs/${id(jobId)}`, { method: 'DELETE' }),
    jobEventsUrl: (streamId: string) => sdk.http.url(`/admin/jobs/${id(streamId)}/events`),
  }
}

export type McWikiApi = ReturnType<typeof createMcWikiApi>
