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
  forceUpdate?: boolean
  enabled?: boolean
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

/** 站内上传制品随文件一起提交的元数据。 */
export interface UploadArtifactFileFields {
  kind?: string
  variant?: string
  platform?: string
  architecture?: string
  targetPlatforms?: string[]
  filename?: string
  signature?: string
}

interface BackendEnvelope<T> {
  code: number
  message: string
  data: T
}

/**
 * 未完结 XHR 的兜底持有集合。Firefox 对每域名并发连接有上限，连接被占满时上传请求会排队；
 * 排队中的 XHR 若只被自身事件回调引用会被 GC 回收，请求以 NS_BINDING_ABORTED 静默中止且
 * 事件不再触发，外层 Promise 永不落定（表现为上传卡死）。完结前显式持有即可避免。
 */
const inflightUploads = new Set<XMLHttpRequest>()

export function createUpdateApi(sdk: YuDreamPluginSdk) {
  const base = sdk.http.url('').replace(/\/$/, '')

  function authHeaders(): Record<string, string> {
    const token = localStorage.getItem('token') || ''
    return token ? { Authorization: token } : {}
  }

  /**
   * 站内制品直传（multipart）。sdk.http 走宿主 axios：60s 超时、无上传进度，
   * base64 JSON 还会放大体积；更新包动辄几十 MB，这里改用 XHR 直打插件 multipart 端点。
   */
  function uploadArtifactFile(
    version: string,
    file: File,
    fields: UploadArtifactFileFields,
    onProgress?: (percent: number) => void,
  ): Promise<UpdateReleaseRecord> {
    const form = new FormData()
    form.append('file', file)
    form.append('filename', fields.filename?.trim() || file.name)
    const pairs: Array<[string, string | undefined]> = [
      ['kind', fields.kind],
      ['variant', fields.variant],
      ['platform', fields.platform],
      ['architecture', fields.architecture],
      ['signature', fields.signature],
    ]
    for (const [key, value] of pairs) {
      if (value && value.trim()) {
        form.append(key, value.trim())
      }
    }
    if (fields.targetPlatforms?.length) {
      form.append('targetPlatforms', fields.targetPlatforms.join(','))
    }

    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      inflightUploads.add(xhr)
      xhr.open('POST', `${base}/admin/update/releases/${encodeURIComponent(version)}/artifacts/upload`)
      xhr.responseType = 'json'
      const headers = authHeaders()
      for (const [key, value] of Object.entries(headers)) {
        xhr.setRequestHeader(key, value)
      }
      xhr.upload.onprogress = (event) => {
        // 传输期间最多报 99，100 留给响应确认后
        if (event.lengthComputable && event.total > 0) {
          onProgress?.(Math.min(99, Math.round((event.loaded / event.total) * 100)))
        }
      }
      xhr.onload = () => {
        inflightUploads.delete(xhr)
        const body = xhr.response as BackendEnvelope<UpdateReleaseRecord> | null
        if (xhr.status >= 200 && xhr.status < 300 && body?.code === 200 && body.data) {
          onProgress?.(100)
          resolve(body.data)
        }
        else {
          reject(new Error(body?.message || `上传失败（HTTP ${xhr.status}）`))
        }
      }
      xhr.onerror = () => {
        inflightUploads.delete(xhr)
        reject(new Error('网络错误，上传失败'))
      }
      xhr.onabort = () => {
        inflightUploads.delete(xhr)
        reject(new Error('上传已取消'))
      }
      xhr.send(form)
    })
  }

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
    uploadArtifactFile,
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
    /** 平台自动渲染的公开更新日志页地址。 */
    changelogUrl: (version: string) =>
      `${base}/v1/update/changelog/${encodeURIComponent(version)}`,
    changelogIndexUrl: () => `${base}/v1/update/changelog`,
  }
}
