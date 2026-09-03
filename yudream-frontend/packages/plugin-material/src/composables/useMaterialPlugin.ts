import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CategoryView, MaterialDetail, MaterialSummary, PreviewInfo, ShareView, TagView, VersionView } from '../types'
import { useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createMaterialApi, saveBlob } from '../api/material-api'

export function useMaterialPlugin(sdk: YuDreamPluginSdk) {
  const api = createMaterialApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)
  const categories = ref<CategoryView[]>([])

  // ---------- 用户端：物料库 ----------
  const library = ref<MaterialSummary[]>([])
  const libraryPager = reactive({ page: 1, size: 24, total: 0 })
  const libraryFilters = reactive({ keyword: '', type: '', categoryId: '', status: '', tag: '' })
  const tags = ref<TagView[]>([])
  /** 封面图：materialId -> 签名公开地址（仅图片类型物料有值）。 */
  const covers = ref<Record<string, string>>({})

  // ---------- 用户端：详情 ----------
  const detail = ref<MaterialDetail | null>(null)
  const versions = ref<VersionView[]>([])
  const preview = ref<PreviewInfo | null>(null)
  const previewLoading = ref(false)

  // ---------- 用户端：分享外链 ----------
  const shares = ref<ShareView[]>([])

  // ---------- 管理端 ----------
  const adminList = ref<MaterialSummary[]>([])
  const adminPager = reactive({ page: 1, size: 20, total: 0 })
  const adminFilters = reactive({ keyword: '', type: '', categoryId: '', owner: '', status: '' })

  function errorMessage(error: unknown) {
    if (error && typeof error === 'object') {
      const data = error as { response?: { data?: { message?: string } }, message?: string }
      return data.response?.data?.message || data.message || '请求失败'
    }
    return '请求失败'
  }

  function clampPage(pager: { page: number, size: number, total: number }, count: number) {
    if (count === 0 && pager.page > 1) {
      pager.page -= 1
      return false
    }
    return true
  }

  async function loadCategories() {
    categories.value = await api.myCategories()
  }

  async function loadLibrary() {
    loading.value = true
    try {
      const page = await api.myMaterials(libraryFilters.keyword, libraryFilters.type, libraryFilters.categoryId,
        libraryFilters.status, libraryFilters.tag, libraryPager.page, libraryPager.size)
      library.value = page.records
      libraryPager.total = Number(page.total)
      void loadCovers()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyLibraryFilters() {
    libraryPager.page = 1
    void loadLibrary()
  }

  /** 侧栏分类导航：选中/清空分类后回到第一页。 */
  function setCategory(categoryId: string) {
    libraryFilters.categoryId = categoryId
    applyLibraryFilters()
  }

  /** 标签云：再次点击同一标签取消筛选。 */
  function toggleTag(name: string) {
    libraryFilters.tag = libraryFilters.tag === name ? '' : name
    applyLibraryFilters()
  }

  async function loadTags() {
    try {
      tags.value = await api.myTags()
    }
    catch {
      tags.value = []
    }
  }

  /** 为当前页的图片物料批量签发封面地址；其余类型前端用类型图标兜底。 */
  async function loadCovers() {
    const ids = library.value.filter(item => item.type === 'IMAGE').map(item => item.id).slice(0, 60)
    if (ids.length === 0) {
      covers.value = {}
      return
    }
    try {
      const records = await api.myCovers(ids)
      const map: Record<string, string> = {}
      for (const record of records) {
        map[record.id] = sdk.files.assetUrl(record.url)
      }
      covers.value = map
    }
    catch {
      covers.value = {}
    }
  }

  async function uploadMaterial(fileId: string, filename: string, name: string, categoryId: string, tags: string[]) {
    try {
      await api.createMaterial({ fileId, filename, name, categoryId: categoryId || undefined, tags })
      toast.success('物料已上传')
      await loadLibrary()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function editMaterial(materialId: string, name: string, categoryId: string, tags: string[]) {
    try {
      await api.updateMaterial(materialId, { name, categoryId: categoryId || null, tags })
      toast.success('物料信息已更新')
      await loadLibrary()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function removeMaterial(row: MaterialSummary) {
    try {
      await api.deleteMaterial(row.id)
      toast.success(`已删除「${row.name}」`)
      if (!clampPage(libraryPager, library.value.length - 1)) {
        /* 回退页码后由 loadLibrary 拉取 */
      }
      await loadLibrary()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function downloadMine(row: MaterialSummary, version?: number) {
    try {
      const blob = await api.downloadMine(row.id, version)
      saveBlob(blob.data, blob.headers, row.name)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 用户端：详情 ----------

  async function loadDetail(materialId: string) {
    loading.value = true
    try {
      detail.value = await api.myDetail(materialId)
      versions.value = await api.myVersions(materialId)
    }
    catch (error) {
      detail.value = null
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function loadPreview(materialId: string, version?: number) {
    previewLoading.value = true
    preview.value = null
    try {
      preview.value = await api.myPreview(materialId, version)
    }
    catch (error) {
      preview.value = { mode: 'NONE', message: errorMessage(error) }
    }
    finally {
      previewLoading.value = false
    }
  }

  async function uploadNewVersion(materialId: string, fileId: string, filename: string, note: string) {
    try {
      detail.value = await api.newVersion(materialId, { fileId, filename, note: note || undefined })
      versions.value = await api.myVersions(materialId)
      toast.success(`已上传 v${detail.value.material.currentVersion}`)
      await loadPreview(materialId)
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function restoreVersion(materialId: string, version: number) {
    try {
      detail.value = await api.restore(materialId, version)
      versions.value = await api.myVersions(materialId)
      toast.success(`已回滚到 v${version}`)
      await loadPreview(materialId)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function removeVersion(materialId: string, version: number) {
    try {
      await api.deleteVersion(materialId, version)
      versions.value = await api.myVersions(materialId)
      toast.success(`已删除 v${version}`)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 用户端：分享外链 ----------

  async function loadShares(materialId: string) {
    try {
      shares.value = await api.myShares(materialId)
    }
    catch (error) {
      shares.value = []
      toast.error(errorMessage(error))
    }
  }

  async function createShare(materialId: string, expiresInHours: number | null, note: string): Promise<ShareView | null> {
    try {
      const share = await api.createShare(materialId, {
        expiresInHours: expiresInHours ?? undefined,
        note: note || undefined,
      })
      toast.success('分享链接已创建')
      await loadShares(materialId)
      return share
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
  }

  async function revokeShare(materialId: string, shareId: string) {
    try {
      await api.revokeShare(materialId, shareId)
      toast.success('分享链接已撤销')
      await loadShares(materialId)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 管理端 ----------

  async function loadAdmin() {
    loading.value = true
    try {
      const page = await api.adminMaterials(adminFilters.keyword, adminFilters.type, adminFilters.categoryId,
        adminFilters.owner, adminFilters.status, adminPager.page, adminPager.size)
      adminList.value = page.records
      adminPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyAdminFilters() {
    adminPager.page = 1
    void loadAdmin()
  }

  async function adminSetStatus(row: MaterialSummary, status: 'ACTIVE' | 'ARCHIVED') {
    try {
      await api.adminSetStatus(row.id, status)
      toast.success(status === 'ARCHIVED' ? `已归档「${row.name}」` : `已恢复「${row.name}」`)
      await loadAdmin()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function adminRemove(row: MaterialSummary) {
    try {
      await api.adminDelete(row.id)
      toast.success(`已删除「${row.name}」`)
      clampPage(adminPager, adminList.value.length - 1)
      await loadAdmin()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function adminDownload(row: MaterialSummary) {
    try {
      const blob = await api.adminDownload(row.id)
      saveBlob(blob.data, blob.headers, row.name)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function adminPreview(row: MaterialSummary): Promise<PreviewInfo> {
    try {
      return await api.adminPreview(row.id)
    }
    catch (error) {
      return { mode: 'NONE', message: errorMessage(error) }
    }
  }

  // ---------- 管理端：分类 ----------

  async function saveCategory(categoryId: string | null, name: string, sort: number) {
    try {
      if (categoryId) {
        await api.updateCategory(categoryId, { name, sort })
      }
      else {
        await api.createCategory({ name, sort })
      }
      toast.success('分类已保存')
      await loadCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function removeCategory(row: CategoryView) {
    try {
      await api.deleteCategory(row.id)
      toast.success(`已删除分类「${row.name}」`)
      await loadCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  const model = reactive({
    api,
    loading,
    categories,
    library,
    libraryPager,
    libraryFilters,
    tags,
    covers,
    detail,
    versions,
    preview,
    previewLoading,
    shares,
    adminList,
    adminPager,
    adminFilters,
    loadCategories,
    loadLibrary,
    applyLibraryFilters,
    setCategory,
    toggleTag,
    loadTags,
    loadCovers,
    uploadMaterial,
    editMaterial,
    removeMaterial,
    downloadMine,
    loadDetail,
    loadPreview,
    uploadNewVersion,
    restoreVersion,
    removeVersion,
    loadShares,
    createShare,
    revokeShare,
    loadAdmin,
    applyAdminFilters,
    adminSetStatus,
    adminRemove,
    adminDownload,
    adminPreview,
    saveCategory,
    removeCategory,
  })
  // sdk 保持原引用不进 reactive 代理，避免宿主注入对象被包装
  return Object.assign(model, { sdk })
}

export type MaterialPluginModel = ReturnType<typeof useMaterialPlugin>
