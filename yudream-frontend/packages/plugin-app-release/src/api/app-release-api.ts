import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AppReleaseSettingsView, AppReleaseUploadPayload, AppReleaseView } from '../types'

/** 列表端点统一返回 {records:[...]}，在此拆包；裸数组响应也兼容。 */
async function records<T>(promise: Promise<unknown>): Promise<T[]> {
  const body = await promise
  if (Array.isArray(body)) {
    return body as T[]
  }
  const wrapped = body as { records?: T[] } | null | undefined
  return Array.isArray(wrapped?.records) ? wrapped.records : []
}

export function errorMessage(error: unknown, fallback: string): string {
  const message = (error as { message?: string } | null | undefined)?.message
  return message && message.trim() ? message : fallback
}

export function createAppReleaseApi(sdk: YuDreamPluginSdk) {
  const { http } = sdk

  return {
    list: () => records<AppReleaseView>(http.get('/admin/releases')),
    upload: (payload: AppReleaseUploadPayload) => {
      const form = new FormData()
      form.append('file', payload.file)
      form.append('platform', payload.platform)
      form.append('versionCode', String(payload.versionCode))
      form.append('versionName', payload.versionName)
      form.append('changelog', payload.changelog)
      form.append('forceUpdate', String(payload.forceUpdate))
      return http.request<AppReleaseView>('/admin/releases', { method: 'POST', data: form })
    },
    publish: (id: string) => http.post<AppReleaseView>(`/admin/releases/${id}/publish`),
    unpublish: (id: string) => http.post<AppReleaseView>(`/admin/releases/${id}/unpublish`),
    remove: (id: string) => http.request<void>(`/admin/releases/${id}`, { method: 'DELETE' }),
    settings: () => http.get<AppReleaseSettingsView>('/admin/settings'),
    saveSettings: (minVersionCode: number) =>
      http.request<AppReleaseSettingsView>('/admin/settings', { method: 'PUT', data: { minVersionCode } }),
    downloadUrl: (id: string) => http.url(`/public/download/${id}`),
  }
}
