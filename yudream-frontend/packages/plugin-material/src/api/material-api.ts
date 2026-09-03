import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CategoryView, CoverView, FolderImportPayload, FolderImportResult, MaterialDetail, MaterialSummary, Page, PreviewInfo, ShareView, TagView, VersionView } from '../types'

function query(params: Record<string, string | number | undefined>) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params)) {
    if (item !== undefined && item !== '') {
      value.set(key, String(item))
    }
  }
  const text = value.toString()
  return text ? `?${text}` : ''
}

const id = (value: string) => encodeURIComponent(value)

/** 简单列表端点后端统一返回 {records:[...]}，在此拆包；裸数组响应也兼容。 */
function records<T>(promise: Promise<unknown>): Promise<T[]> {
  return promise.then((body) => {
    if (Array.isArray(body)) {
      return body as T[]
    }
    const wrapped = body as { records?: T[] } | null | undefined
    return Array.isArray(wrapped?.records) ? wrapped.records : []
  })
}

export function createMaterialApi(sdk: YuDreamPluginSdk) {
  return {
    // ---------- 用户端（/me/**，列表默认返回全部可见物料，scope=mine 只列自己的） ----------
    myMaterials: (keyword = '', type = '', categoryId = '', status = '', tag = '', page = 1, size = 20, scope = '') =>
      sdk.http.get<Page<MaterialSummary>>(`/me/materials${query({ keyword, type, categoryId, status, tag, page, size, scope })}`),
    myDetail: (materialId: string) => sdk.http.get<MaterialDetail>(`/me/materials/${id(materialId)}`),
    createMaterial: (data: { fileId: string, filename: string, name?: string, categoryId?: string, tags?: string[], visibility?: string }) =>
      sdk.http.post<MaterialDetail>('/me/materials', data),
    importFolder: (data: FolderImportPayload) =>
      sdk.http.post<FolderImportResult>('/me/materials/import-folder', data),
    updateMaterial: (materialId: string, data: { name?: string, categoryId?: string | null, tags?: string[], visibility?: string }) =>
      sdk.http.request<MaterialDetail>(`/me/materials/${id(materialId)}`, { method: 'PUT', data }),
    deleteMaterial: (materialId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/me/materials/${id(materialId)}`, { method: 'DELETE' }),
    myVersions: (materialId: string) => records<VersionView>(sdk.http.get(`/me/materials/${id(materialId)}/versions`)),
    newVersion: (materialId: string, data: { fileId: string, filename: string, note?: string }) =>
      sdk.http.post<MaterialDetail>(`/me/materials/${id(materialId)}/versions`, data),
    restore: (materialId: string, version: number) =>
      sdk.http.post<MaterialDetail>(`/me/materials/${id(materialId)}/restore`, { version }),
    deleteVersion: (materialId: string, version: number) =>
      sdk.http.request<{ deleted: boolean }>(`/me/materials/${id(materialId)}/versions/${version}`, { method: 'DELETE' }),
    myPreview: (materialId: string, version?: number) =>
      sdk.http.get<PreviewInfo>(`/me/materials/${id(materialId)}/preview${query({ version })}`),
    downloadMine: (materialId: string, version?: number) =>
      sdk.http.blob(`/me/materials/${id(materialId)}/download${query({ version })}`),
    myShares: (materialId: string) => records<ShareView>(sdk.http.get(`/me/materials/${id(materialId)}/shares`)),
    createShare: (materialId: string, data: { expiresInHours?: number, note?: string }) =>
      sdk.http.post<ShareView>(`/me/materials/${id(materialId)}/shares`, data),
    revokeShare: (materialId: string, shareId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/me/materials/${id(materialId)}/shares/${id(shareId)}`, { method: 'DELETE' }),
    myCategories: () => records<CategoryView>(sdk.http.get('/me/categories')),
    myTags: () => records<TagView>(sdk.http.get('/me/tags')),
    myCovers: (ids: string[]) => records<CoverView>(sdk.http.get(`/me/covers${query({ ids: ids.join(',') })}`)),
    // ---------- 管理端（/admin/**，跨用户） ----------
    adminMaterials: (keyword = '', type = '', categoryId = '', owner = '', status = '', page = 1, size = 20) =>
      sdk.http.get<Page<MaterialSummary>>(`/admin/materials${query({ keyword, type, categoryId, owner, status, page, size })}`),
    adminDetail: (materialId: string) => sdk.http.get<MaterialDetail>(`/admin/materials/${id(materialId)}`),
    adminVersions: (materialId: string) => records<VersionView>(sdk.http.get(`/admin/materials/${id(materialId)}/versions`)),
    adminSetStatus: (materialId: string, status: 'ACTIVE' | 'ARCHIVED') =>
      sdk.http.request<MaterialDetail>(`/admin/materials/${id(materialId)}/status`, { method: 'PUT', data: { status } }),
    adminDelete: (materialId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/materials/${id(materialId)}`, { method: 'DELETE' }),
    adminPreview: (materialId: string, version?: number) =>
      sdk.http.get<PreviewInfo>(`/admin/materials/${id(materialId)}/preview${query({ version })}`),
    adminDownload: (materialId: string, version?: number) =>
      sdk.http.blob(`/admin/materials/${id(materialId)}/download${query({ version })}`),
    adminCategories: () => records<CategoryView>(sdk.http.get('/admin/categories')),
    createCategory: (data: { name: string, sort?: number }) => sdk.http.post<CategoryView>('/admin/categories', data),
    updateCategory: (categoryId: string, data: { name?: string, sort?: number }) =>
      sdk.http.request<CategoryView>(`/admin/categories/${id(categoryId)}`, { method: 'PUT', data }),
    deleteCategory: (categoryId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/categories/${id(categoryId)}`, { method: 'DELETE' }),
  }
}

export type MaterialApi = ReturnType<typeof createMaterialApi>

/** 用浏览器下载 blob 响应（下载端点带权限，不能裸 <a href>）。 */
export function saveBlob(data: Blob, headers: Record<string, string>, fallbackName: string) {
  const disposition = headers['content-disposition'] || ''
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const filename = match ? decodeURIComponent(match[1]) : fallbackName
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
