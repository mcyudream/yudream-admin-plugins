import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AccessControl, ConnectivityResult, GateConfig, OidcRegisterResult, SsoSettings, StudentPage, StudentProfile } from '../types'

export function createCasApi(sdk: YuDreamPluginSdk) {
  const { http } = sdk

  return {
    settings: () => http.get<SsoSettings>('/admin/settings'),
    saveSettings: (data: SsoSettings, clientSecret?: string) =>
      http.request<SsoSettings>('/admin/settings', {
        method: 'PUT',
        data: {
          enabled: data.enabled,
          protocol: data.protocol,
          displayName: data.displayName,
          icon: data.icon,
          casBaseUrl: data.casBaseUrl,
          loginPath: data.loginPath,
          validatePath: data.validatePath,
          oidcIssuer: data.oidcIssuer,
          oidcAuthorizePath: data.oidcAuthorizePath,
          oidcTokenPath: data.oidcTokenPath,
          oidcUserinfoPath: data.oidcUserinfoPath,
          oidcJwksPath: data.oidcJwksPath,
          oidcRegisterPath: data.oidcRegisterPath,
          clientId: data.clientId,
          clientSecret: clientSecret || undefined,
          scopes: data.scopes,
          callbackUrl: data.callbackUrl,
          loginWarmup: data.loginWarmup,
        },
      }),
    test: () => http.post<ConnectivityResult>('/admin/test', {}),
    registerOidc: (clientName?: string) =>
      http.post<OidcRegisterResult>('/admin/oidc/register', { clientName }),
    accessControl: () => http.get<AccessControl>('/admin/access-control'),
    saveAccessControl: (data: AccessControl) =>
      http.request<AccessControl>('/admin/access-control', { method: 'PUT', data }),
    students: (page: number, size: number, keyword?: string) =>
      http.get<StudentPage>(`/admin/students?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`),
    studentDetail: (socialUid: string) =>
      http.get<StudentProfile>(`/admin/students/detail?socialUid=${encodeURIComponent(socialUid)}`),
    gate: () => http.get<GateConfig>('/public/gate'),
  }
}

export type CasApi = ReturnType<typeof createCasApi>
