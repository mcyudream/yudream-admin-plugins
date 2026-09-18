import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export type UpdateChannel = 'release' | 'beta'

/** Keep a Changelog 分类，与 Axolotl 更新公告一致。 */
export type UpdateChangeCategory =
  | 'added'
  | 'changed'
  | 'deprecated'
  | 'removed'
  | 'fixed'
  | 'security'

export type UpdateChanges = Partial<Record<UpdateChangeCategory, string[]>>

export const UPDATE_CHANGE_CATEGORIES: Array<{
  key: UpdateChangeCategory
  label: string
  color: string
}> = [
  { key: 'added', label: '新增', color: '#22c55e' },
  { key: 'changed', label: '变更', color: '#3b82f6' },
  { key: 'deprecated', label: '废弃', color: '#a855f7' },
  { key: 'removed', label: '移除', color: '#ef4444' },
  { key: 'fixed', label: 'Bug 修复', color: '#a855f7' },
  { key: 'security', label: '安全', color: '#f59e0b' },
]

export const CHANNEL_OPTIONS = [
  { label: '正式版（release）', value: 'release' },
  { label: '测试版（beta）', value: 'beta' },
] as const

export interface UpdateArtifactRecord {
  id: string
  kind: string
  variant?: string
  platform?: string
  architecture?: string
  targetPlatforms?: string[]
  filename: string
  downloadUrl?: string | null
  fileKey?: string | null
  hostedLocally?: boolean
  sha256?: string | null
  size?: number
  signature?: string | null
  contentType?: string
  createdAt?: string
}

export interface UpdateReleaseRecord {
  version: string
  channel: UpdateChannel | string
  title?: string
  notes?: string
  changes?: UpdateChanges
  publishedAt?: string
  forceUpdate?: boolean
  yanked?: boolean
  enabled?: boolean
  externalUrl?: string
  updatedAt?: string
  artifacts: UpdateArtifactRecord[]
}

export interface SaveReleasePayload {
  version: string
  channel?: string
  title?: string
  notes?: string
  changes?: UpdateChanges
  publishedAt?: string
  forceUpdate?: boolean
  enabled?: boolean
  externalUrl?: string
}

export interface SaveUrlArtifactPayload {
  kind: string
  variant?: string
  platform?: string
  architecture?: string
  targetPlatforms?: string[]
  filename: string
  downloadUrl: string
  signature?: string
  sha256?: string
  size?: number
}

export interface SaveFileArtifactPayload extends Partial<SaveUrlArtifactPayload> {
  mode: 'file'
  filename: string
  /** base64 file bytes */
  data: string
}

export function createUpdateApi(sdk: YuDreamPluginSdk) {
  return {
    list: async () => {
      return await sdk.http.get<{ records: UpdateReleaseRecord[]; total: number }>(
        '/admin/update/releases',
      )
    },
    get: async (version: string) => {
      return await sdk.http.get<UpdateReleaseRecord>(
        `/admin/update/releases/${encodeURIComponent(version)}`,
      )
    },
    saveRelease: async (payload: SaveReleasePayload) => {
      return await sdk.http.request<UpdateReleaseRecord>('/admin/update/releases', {
        method: 'POST',
        data: payload,
      })
    },
    saveUrlArtifact: async (version: string, payload: SaveUrlArtifactPayload) => {
      return await sdk.http.request<UpdateReleaseRecord>(
        `/admin/update/releases/${encodeURIComponent(version)}/artifacts`,
        { method: 'POST', data: payload },
      )
    },
    saveFileArtifact: async (version: string, payload: SaveFileArtifactPayload) => {
      return await sdk.http.request<UpdateReleaseRecord>(
        `/admin/update/releases/${encodeURIComponent(version)}/artifacts`,
        { method: 'POST', data: payload },
      )
    },
    removeArtifact: async (version: string, artifactId: string) => {
      return await sdk.http.request<UpdateReleaseRecord>(
        `/admin/update/releases/${encodeURIComponent(version)}/artifacts/${encodeURIComponent(artifactId)}`,
        { method: 'DELETE' },
      )
    },
    setYanked: async (version: string, yanked: boolean) => {
      return await sdk.http.request<UpdateReleaseRecord>(
        `/admin/update/releases/${encodeURIComponent(version)}/yank`,
        { method: 'POST', data: { yanked } },
      )
    },
    removeRelease: async (version: string) => {
      return await sdk.http.request<{ deleted: boolean; version: string }>(
        `/admin/update/releases/${encodeURIComponent(version)}`,
        { method: 'DELETE' },
      )
    },
  }
}
