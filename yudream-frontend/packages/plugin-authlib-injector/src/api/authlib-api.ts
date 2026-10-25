import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AuthlibDisplaySettings, AuthlibStatus } from '../types'

export function createAuthlibApi(sdk: YuDreamPluginSdk) {
  return {
    status: () => sdk.http.get<AuthlibStatus>('/admin/status'),
    settings: () => sdk.http.get<AuthlibDisplaySettings>('/admin/settings'),
    saveSettings: (data: { serverName: string }) =>
      sdk.http.request<AuthlibDisplaySettings>('/admin/settings', { method: 'PUT', data }),
    apiUrl: (path = '/') => sdk.http.url(path),
  }
}
