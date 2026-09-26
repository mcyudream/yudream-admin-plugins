import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export type ContentKind = 'guides' | 'updates' | 'online'

export const CONTENT_KIND_OPTIONS = [
  { label: '更新公告（updates）', value: 'updates' },
  { label: '新手指引（guides）', value: 'guides' },
  { label: '在线内容（online）', value: 'online' },
] as const

export interface ContentRecord {
  id: string
  kind: ContentKind | string
  title: string
  summary?: string
  body?: string
  coverUrl?: string
  url?: string
  meta?: string
  sort?: number
  enabled?: boolean
  updatedAt?: string
}

export interface ContentRecordPayload {
  id?: string
  kind: ContentKind | string
  title: string
  summary?: string
  body?: string
  coverUrl?: string
  url?: string
  meta?: string
  sort?: number
  enabled?: boolean
}

export interface ContentRecordPage {
  records: ContentRecord[]
  total: number
  page: number
  size: number
}

export function createContentApi(sdk: YuDreamPluginSdk) {
  return {
    list: async (params: { kind?: string; page?: number; size?: number }) => {
      const query = new URLSearchParams()
      if (params.kind) query.set('kind', params.kind)
      query.set('page', String(params.page ?? 1))
      query.set('size', String(params.size ?? 10))
      return await sdk.http.get<ContentRecordPage>(
        `/admin/content/records?${query.toString()}`,
      )
    },
    save: async (payload: ContentRecordPayload) => {
      return await sdk.http.request<ContentRecord>('/admin/content/records', {
        method: 'POST',
        data: payload,
      })
    },
    remove: async (id: string) => {
      return await sdk.http.request<{ deleted: boolean; id: string }>(
        `/admin/content/records/${encodeURIComponent(id)}`,
        { method: 'DELETE' },
      )
    },
  }
}
