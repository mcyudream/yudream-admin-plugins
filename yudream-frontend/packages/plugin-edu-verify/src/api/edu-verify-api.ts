import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  AdminPage,
  ChsiVerifyResult,
  DomainRecord,
  EmailSendResult,
  ManualMaterialPayload,
  PublicMethods,
  StatusPayload,
  VerificationRecord,
  VerifySettings,
} from '../types'

function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

function records<T>(promise: Promise<unknown>): Promise<T[]> {
  return promise.then((body) => {
    if (Array.isArray(body)) {
      return body as T[]
    }
    const wrapped = body as { records?: T[] } | null | undefined
    return Array.isArray(wrapped?.records) ? wrapped.records : []
  })
}

export function createEduVerifyApi(sdk: YuDreamPluginSdk) {
  const { http } = sdk

  return {
    publicMethods: () => http.get<PublicMethods>('/public/methods'),
    publicStatus: (email: string) => http.get<StatusPayload>(`/public/status${query({ email })}`),
    sendEmailCode: (email: string) => http.post<EmailSendResult>('/public/email/send-code', { email }),
    verifyEmailCode: (email: string, code: string) => http.post<VerificationRecord>('/public/email/verify', { email, code }),
    chsiVerify: (email: string, vcode: string, realName: string, schoolName: string) =>
      http.post<ChsiVerifyResult>('/public/chsi/verify', { email, vcode, realName, schoolName }),
    chsiConfirm: (email: string) => http.post<ChsiVerifyResult>('/public/chsi/confirm', { email }),
    publicManualSubmit: (payload: {
      email: string
      realName: string
      schoolName: string
      note?: string
      vcode?: string
      materials: ManualMaterialPayload[]
    }) => http.post<VerificationRecord>('/public/manual/submit', payload),

    adminPage: (status: string, channel: string, keyword: string, page: number, size: number) =>
      http.get<AdminPage>(`/admin/verifications${query({ status, channel, keyword, page, size })}`),
    adminDetail: (id: string) => http.get<VerificationRecord>(`/admin/verifications/${encodeURIComponent(id)}`),
    approve: (id: string, payload?: { reason?: string, realName?: string, schoolName?: string }) =>
      http.post<VerificationRecord>(`/admin/verifications/${encodeURIComponent(id)}/approve`, payload || {}),
    reject: (id: string, reason: string) =>
      http.post<VerificationRecord>(`/admin/verifications/${encodeURIComponent(id)}/reject`, { reason }),
    revoke: (id: string, reason: string) =>
      http.post<VerificationRecord>(`/admin/verifications/${encodeURIComponent(id)}/revoke`, { reason }),

    domains: () => records<DomainRecord>(http.get('/admin/domains')),
    saveDomain: (domain: string, chineseName: string, englishName: string, enabled: boolean, source?: string) =>
      http.request<DomainRecord>('/admin/domains', { method: 'PUT', data: { domain, chineseName, englishName, enabled, source } }),
    deleteDomain: (domain: string) =>
      http.request<{ deleted: boolean }>(`/admin/domains/${encodeURIComponent(domain)}`, { method: 'DELETE' }),

    settings: () => http.get<VerifySettings>('/admin/settings'),
    saveSettings: (data: VerifySettings) => http.request<VerifySettings>('/admin/settings', { method: 'PUT', data }),
  }
}

export type EduVerifyApi = ReturnType<typeof createEduVerifyApi>
