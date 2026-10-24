import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  AiProviderOption,
  CredentialKind,
  DatasetRecord,
  DocJob,
  FinanceAttachment,
  FinanceRecord,
  OfficeCategory,
  OfficeDocType,
  OfficePackage,
  OfficePrompt,
  OfficeSchema,
  OfficeSettings,
  PageResult,
  PrecheckRow,
  PresetInfo,
  PreviewResult,
  SettlementBatch,
  SettlementOverview,
  WordTemplateOption,
} from '../types'

/** 查询串：跳过空值。 */
function query(params: Record<string, string | number | boolean | undefined>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  })
  const value = search.toString()
  return value ? `?${value}` : ''
}

export function createOfficeAdminApi(sdk: YuDreamPluginSdk) {
  return {
    settings: () => sdk.http.get<OfficeSettings>('/admin/settings'),
    saveSettings: (data: Partial<OfficeSettings>) =>
      sdk.http.request<OfficeSettings>('/admin/settings', { method: 'PUT', data }),

    schemas: () => sdk.http.get<OfficeSchema[]>('/admin/schemas'),
    saveSchema: (data: Partial<OfficeSchema>) => sdk.http.post<OfficeSchema>('/admin/schemas', data),
    updateSchema: (id: string, data: Partial<OfficeSchema>) =>
      sdk.http.request<OfficeSchema>(`/admin/schemas/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteSchema: (id: string) =>
      sdk.http.request(`/admin/schemas/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    docTypes: () => sdk.http.get<OfficeDocType[]>('/admin/doc-types'),
    saveDocType: (data: Partial<OfficeDocType>) => sdk.http.post<OfficeDocType>('/admin/doc-types', data),
    updateDocType: (id: string, data: Partial<OfficeDocType>) =>
      sdk.http.request<OfficeDocType>(`/admin/doc-types/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteDocType: (id: string) =>
      sdk.http.request(`/admin/doc-types/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    prompts: () => sdk.http.get<OfficePrompt[]>('/admin/prompts'),
    savePrompt: (data: Partial<OfficePrompt>) => sdk.http.post<OfficePrompt>('/admin/prompts', data),
    updatePrompt: (id: string, data: Partial<OfficePrompt>) =>
      sdk.http.request<OfficePrompt>(`/admin/prompts/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deletePrompt: (id: string) =>
      sdk.http.request(`/admin/prompts/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    categories: () => sdk.http.get<OfficeCategory[]>('/admin/categories'),
    saveCategory: (data: Partial<OfficeCategory>) => sdk.http.post<OfficeCategory>('/admin/categories', data),
    updateCategory: (id: string, data: Partial<OfficeCategory>) =>
      sdk.http.request<OfficeCategory>(`/admin/categories/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteCategory: (id: string) =>
      sdk.http.request(`/admin/categories/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    packages: () => sdk.http.get<OfficePackage[]>('/admin/packages'),
    savePackage: (data: Partial<OfficePackage>) => sdk.http.post<OfficePackage>('/admin/packages', data),
    updatePackage: (id: string, data: Partial<OfficePackage>) =>
      sdk.http.request<OfficePackage>(`/admin/packages/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deletePackage: (id: string) =>
      sdk.http.request(`/admin/packages/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    dataset: (schemaCode: string, page = 1, size = 10) =>
      sdk.http.get<PageResult<DatasetRecord>>(`/admin/dataset${query({ schemaCode, page, size })}`),
    saveDataset: (data: Partial<DatasetRecord>) => sdk.http.post<DatasetRecord>('/admin/dataset', data),
    updateDataset: (id: string, data: Partial<DatasetRecord>) =>
      sdk.http.request<DatasetRecord>(`/admin/dataset/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteDataset: (id: string) =>
      sdk.http.request(`/admin/dataset/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    wordTemplates: () => sdk.http.get<WordTemplateOption[]>('/admin/word-templates'),
    aiProviders: () => sdk.http.get<AiProviderOption[]>('/admin/ai/providers'),

    presets: () => sdk.http.get<PresetInfo[]>('/admin/presets'),
    importPreset: (code: string, overwrite: boolean) =>
      sdk.http.post<{ created: number, skipped: number, preset: string }>('/admin/presets/import',
        { code, overwrite }),
    uploadPreset: async (file: File, overwrite: boolean) => {
      const uploaded = await sdk.files.uploadImage(file, { module: 'ai-office' })
      return sdk.http.post<{ created: number, skipped: number, preset: string }>(
        `/admin/presets/upload${query({ overwrite: overwrite || undefined })}`,
        { fileId: uploaded.id, filename: uploaded.originalName || file.name })
    },
    exportPreset: () => sdk.http.blob('/admin/presets/export'),

    finance: (params: { direction?: string, status?: string, categoryCode?: string, page?: number, size?: number } = {}) =>
      sdk.http.get<PageResult<FinanceRecord>>(`/admin/finance${query({ direction: params.direction, status: params.status, categoryCode: params.categoryCode, page: params.page ?? 1, size: params.size ?? 10 })}`),
    createFinance: (data: Partial<FinanceRecord>, acceptNow = false) =>
      sdk.http.post<FinanceRecord>(`/admin/finance${query({ accept: acceptNow || undefined })}`, data),
    reviewFinance: (id: string, data: { accept: boolean, note?: string }) =>
      sdk.http.post<FinanceRecord>(`/admin/finance/${encodeURIComponent(id)}/review`, data),
    deleteFinance: (id: string) =>
      sdk.http.request(`/admin/finance/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    uploadFinanceAttachment: async (file: File, kind?: string): Promise<FinanceAttachment> => {
      const uploaded = await sdk.files.uploadImage(file, { module: 'ai-office' })
      return sdk.http.post<FinanceAttachment>(
        `/me/finance/upload${query({ kind: kind || undefined })}`,
        { fileId: uploaded.id, filename: uploaded.originalName || file.name })
    },

    settlements: () => sdk.http.get<SettlementBatch[]>('/admin/settlements'),
    settlementOverview: (period?: string) =>
      sdk.http.get<SettlementOverview>(`/admin/settlements/overview${query({ period })}`),
    closeSettlement: (period: string) =>
      sdk.http.post<SettlementBatch>('/admin/settlements/close', { period }),

    generateContext: (sourceType: string, sourceId: string, period: string) =>
      sdk.http.get<Record<string, unknown>>(`/admin/generate/context${query({ sourceType, sourceId, period })}`),
    preview: (data: { docTypeCode: string, sourceType: string, sourceId: string, period: string }) =>
      sdk.http.post<PreviewResult>('/admin/generate/preview', data),
    jobs: (params: { docTypeCode?: string, status?: string, page?: number, size?: number } = {}) =>
      sdk.http.get<PageResult<DocJob>>(`/admin/generate/jobs${query({ docTypeCode: params.docTypeCode, status: params.status, page: params.page ?? 1, size: params.size ?? 10 })}`),
    createJob: (data: { docTypeCode: string, sourceType: string, sourceId: string, period: string }) =>
      sdk.http.post<DocJob>('/admin/generate/jobs', data),
    job: (id: string) => sdk.http.get<DocJob>(`/admin/generate/jobs/${encodeURIComponent(id)}`),
    deleteJob: (id: string) =>
      sdk.http.request(`/admin/generate/jobs/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    runJobAi: (id: string) => sdk.http.post<DocJob>(`/admin/generate/jobs/${encodeURIComponent(id)}/ai`),
    saveJobDraft: (id: string, draft: Record<string, string>) =>
      sdk.http.request<DocJob>(`/admin/generate/jobs/${encodeURIComponent(id)}/draft`, { method: 'PUT', data: { draft } }),
    renderJob: (id: string) => sdk.http.post<DocJob>(`/admin/generate/jobs/${encodeURIComponent(id)}/render`),
    downloadJob: (id: string) => sdk.http.blob(`/admin/generate/jobs/${encodeURIComponent(id)}/download`),

    packagePrecheck: (code: string, period: string) =>
      sdk.http.get<PrecheckRow[]>(`/admin/packages/${encodeURIComponent(code)}/precheck${query({ period })}`),
    exportPackage: (code: string, period: string, skipMissing: boolean) =>
      sdk.http.blob(`/admin/packages/${encodeURIComponent(code)}/export${query({ period, skipMissing })}`),
  }
}


export function createOfficeMeApi(sdk: YuDreamPluginSdk) {
  return {
    finance: (page = 1, size = 10) =>
      sdk.http.get<PageResult<FinanceRecord>>(`/me/finance${query({ page, size })}`),
    createFinance: (data: Partial<FinanceRecord>) => sdk.http.post<FinanceRecord>('/me/finance', data),
    withdrawFinance: (id: string) =>
      sdk.http.request(`/me/finance/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /** 启用中的报销类别（用户面下拉，走 /me 端点不要求管理权限）。 */
    categories: (direction?: string) =>
      sdk.http.get<OfficeCategory[]>(`/me/finance/categories${query({ direction })}`),
    /** 凭证类型字典（站点设置的数据驱动字典）。 */
    credentialKinds: () =>
      sdk.http.get<CredentialKind[]>('/me/finance/credential-kinds'),
    uploadAttachment: async (file: File, kind?: string): Promise<FinanceAttachment> => {
      const uploaded = await sdk.files.uploadImage(file, { module: 'ai-office' })
      return sdk.http.post<FinanceAttachment>(
        `/me/finance/upload${query({ kind: kind || undefined })}`,
        { fileId: uploaded.id, filename: uploaded.originalName || file.name })
    },
  }
}

export type OfficeAdminApi = ReturnType<typeof createOfficeAdminApi>
export type OfficeMeApi = ReturnType<typeof createOfficeMeApi>

export function errorMessage(cause: unknown, fallback: string): string {
  const data = cause as { response?: { data?: { message?: unknown } }, message?: unknown } | null
  const bodyMessage = data?.response?.data?.message
  if (typeof bodyMessage === 'string' && bodyMessage.trim()) {
    return bodyMessage
  }
  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message
  }
  return fallback
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
