import type {
  ActivityProofExportRecord,
  ActivityProofMapping,
  ActivityProofParticipant,
  ActivityProofServer,
  ActivityProofSettings,
  ActivityProofStatus,
  ActivityProofTemplate,
  ExportForm,
  PageResult,
} from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export function createActivityProofApi(sdk: YuDreamPluginSdk) {
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

  return {
    status: () => sdk.http.get<ActivityProofStatus>('/admin/status'),
    servers: () => sdk.http.get<ActivityProofServer[]>('/admin/servers'),
    templates: (keyword = '') => sdk.http.get<ActivityProofTemplate[]>(`/admin/templates${query({ keyword, page: 1, size: 200 })}`),
    settings: () => sdk.http.get<ActivityProofSettings>('/admin/settings'),
    saveSettings: (data: Record<string, unknown>) => sdk.http.request<ActivityProofSettings>('/admin/settings', { method: 'PUT', data }),
    selectTemplate: (templateId: string) => sdk.http.request<ActivityProofSettings>('/admin/template', { method: 'PUT', data: { templateId } }),
    mappings: (serverId: string, page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofMapping>>(`/admin/mappings${query({ serverId, page, size })}`),
    saveMapping: (data: Record<string, unknown>) => sdk.http.request<ActivityProofMapping>('/admin/mappings', { method: 'PUT', data }),
    deleteMapping: (id: string) => sdk.http.request(`/admin/mappings/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    participants: (serverId: string, minOnlineMinutes: number, includeAfk: boolean, page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofParticipant>>(`/admin/participants${query({ serverId, minOnlineMinutes, includeAfk, page, size })}`),
    exportWord: (data: ExportForm & { serverId: string, selectedPlayerIds: string[] }) => sdk.http.post<ActivityProofExportRecord>('/admin/exports', data),
    exports: (page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofExportRecord>>(`/admin/exports${query({ page, size })}`),
    myExports: (page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofExportRecord>>(`/me/exports${query({ page, size })}`),
    uploadStampedPdf: (id: string, data: Record<string, unknown>) => sdk.http.request<ActivityProofExportRecord>(`/admin/exports/${encodeURIComponent(id)}/stamped-pdf`, { method: 'PUT', data }),
    deleteExport: (id: string) => sdk.http.request(`/admin/exports/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    download: (path: string) => sdk.http.blob(path),
    downloadUrl: (path: string) => sdk.http.url(path),
  }
}
