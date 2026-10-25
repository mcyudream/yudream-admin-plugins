import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AuthlibDisplaySettings, AuthlibStatus, SigningKeyInfo } from '../types'

export function createAuthlibApi(sdk: YuDreamPluginSdk) {
  return {
    status: () => sdk.http.get<AuthlibStatus>('/admin/status'),
    settings: () => sdk.http.get<AuthlibDisplaySettings>('/admin/settings'),
    saveSettings: (data: { serverName: string }) =>
      sdk.http.request<AuthlibDisplaySettings>('/admin/settings', { method: 'PUT', data }),
    signingKey: () => sdk.http.get<SigningKeyInfo>('/admin/signing-key'),
    importSigningKey: (data: { publicKeyPem: string, privateKeyPem: string }) =>
      sdk.http.request<SigningKeyInfo>('/admin/signing-key', { method: 'PUT', data }),
    resetSigningKey: () => sdk.http.request<SigningKeyInfo>('/admin/signing-key', { method: 'DELETE' }),
    apiUrl: (path = '/') => sdk.http.url(path),
  }
}
