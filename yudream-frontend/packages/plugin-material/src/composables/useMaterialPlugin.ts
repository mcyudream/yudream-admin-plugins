import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { BatchResult, CategoryView, DeptOption, FolderImportPayload, FolderImportResult, MaterialDetail, MaterialSummary, PreviewInfo, ShareView, TagView, VersionView } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createMaterialApi, saveBlob } from '../api/material-api'

export function useMaterialPlugin(sdk: YuDreamPluginSdk) {
  const api = createMaterialApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)
  const categories = ref<CategoryView[]>([])

  // ---------- 用户端：物料库 ----------
  const library = ref<MaterialSummary[]>([])
  const libraryPager = reactive({ page: 1, size: 24, total: 0 })
  const libraryFilters = reactive({ keyword: '', type: '', categoryId: '', status: '', tag: '', scope: '' })
  const tags = ref<TagView[]>([])
  /** 封面图：materialId -> 签名公开地址（仅图片类型物料有值）。 */
  const covers = ref<Record<string, string>>({})
  let librarySeq = 0
  let coverSeq = 0

  // ---------- 用户端：详情 ----------
  const detail = ref<MaterialDetail | null>(null)
  const versions = ref<VersionView[]>([])
  const preview = ref<PreviewInfo | null>(null)
  const previewLoading = ref(false)

  // ---------- 用户端：分享外链 ----------
  const shares = ref<ShareView[]>([])

  // ---------- 部门选项（可见范围=仅部门时的选择器数据源） ----------
  /** 用户端：仅当前用户自己加入的部门 */
  const myDeptOptions = ref<DeptOption[]>([])
  /** 管理端：全量部门树拍平（label 带父级路径） */
  const adminDeptOptions = ref<DeptOption[]>([])

  /** 是否具备物料管理权限：管理者可代操作他人物料（编辑/新版本/分享，走 /admin 端点）。 */
  const hasManage = computed(() =>
    sdk.account.permissions.includes('*') || sdk.account.permissions.includes('plugin:material:manage'))

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
    const seq = ++librarySeq
    loading.value = true
    try {
      const page = await api.myMaterials(libraryFilters.keyword, libraryFilters.type, libraryFilters.categoryId,
        libraryFilters.status, libraryFilters.tag, libraryPager.page, libraryPager.size, libraryFilters.scope)
      if (seq !== librarySeq) {
        return
      }
      library.value = page.records
      libraryPager.total = Number(page.total)
      void loadCovers()
    }
    catch (error) {
      if (seq === librarySeq) {
        toast.error(errorMessage(error))
      }
    }
    finally {
      if (seq === librarySeq) {
        loading.value = false
      }
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

  /** 为当前页的图片物料批量签发缩略图地址；无封面时前端用类型图标兜底。 */
  async function loadCovers() {
    const seq = ++coverSeq
    const visibleIds = new Set(library.value.map(item => item.id))
    const ids = library.value.filter(item => item.type === 'IMAGE').map(item => item.id).slice(0, 60)
    if (ids.length === 0) {
      if (seq === coverSeq) {
        covers.value = {}
      }
      return
    }
    try {
      const records = await api.myCovers(ids)
      if (seq !== coverSeq) {
        return
      }
      const next: Record<string, string> = { ...covers.value }
      for (const key of Object.keys(next)) {
        if (!visibleIds.has(key)) {
          delete next[key]
        }
      }
      for (const record of records) {
        next[record.id] = sdk.files.assetUrl(record.url)
      }
      covers.value = next
    }
    catch {
      if (seq === coverSeq) {
        covers.value = {}
      }
    }
  }

  async function uploadMaterial(fileId: string, filename: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[]) {
    try {
      await api.createMaterial({ fileId, filename, name, categoryId: categoryId || undefined, tags, visibility: visibility || undefined, deptIds })
      toast.success('物料已上传')
      await loadLibrary()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  /** 文件夹批量导入：分类按名复用或自动创建（可能新增分类），返回 null 表示请求失败。 */
  async function importFolder(payload: FolderImportPayload): Promise<FolderImportResult | null> {
    try {
      const result = await api.importFolder(payload)
      const target = result.categoryName ? `到「${result.categoryName}」` : ''
      if (result.failures.length) {
        toast.warning(`已导入 ${result.created}/${result.total} 个文件${target}，失败清单见导入窗口`)
      }
      else {
        toast.success(`已导入 ${result.created} 个文件${target}`)
      }
      await loadLibrary()
      void loadTags()
      void loadCategories()
      return result
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
  }

  async function editMaterial(materialId: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[]) {
    try {
      await api.updateMaterial(materialId, { name, categoryId: categoryId || null, tags, visibility: visibility || undefined, deptIds })
      toast.success('物料信息已更新')
      await loadLibrary()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  /** 是否当前用户本人的物料：他人物料只读（预览/下载/版本列表），变更操作仅属主可用。 */
  function isOwner(row: { ownerId: string }) {
    return String(row.ownerId) === sdk.account.userId
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

  // ---------- 用户端：分享外链（admin=true 时切换为管理端代管接口） ----------

  async function loadShares(materialId: string, admin = false) {
    try {
      shares.value = admin ? await api.adminShares(materialId) : await api.myShares(materialId)
    }
    catch (error) {
      shares.value = []
      toast.error(errorMessage(error))
    }
  }

  async function createShare(materialId: string, expiresInHours: number | null, note: string, admin = false): Promise<ShareView | null> {
    try {
      const data = { expiresInHours: expiresInHours ?? undefined, note: note || undefined }
      const share = admin ? await api.adminCreateShare(materialId, data) : await api.createShare(materialId, data)
      toast.success('分享链接已创建')
      await loadShares(materialId, admin)
      return share
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
  }

  async function revokeShare(materialId: string, shareId: string, admin = false) {
    try {
      if (admin) {
        await api.adminRevokeShare(materialId, shareId)
      }
      else {
        await api.revokeShare(materialId, shareId)
      }
      toast.success('分享链接已撤销')
      await loadShares(materialId, admin)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 部门选项 ----------

  async function loadMyDepartments() {
    try {
      myDeptOptions.value = await api.myDepartments()
    }
    catch {
      myDeptOptions.value = []
    }
  }

  async function loadAdminDepartments(keyword = '') {
    try {
      adminDeptOptions.value = await api.adminDepartments(keyword)
    }
    catch {
      adminDeptOptions.value = []
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

  // ---------- 管理端：代操作（编辑/新版本，不校验归属） ----------

  /** 代编辑元数据与可见范围；抛错由调用方留在弹窗内，成功后的列表刷新也由调用方决定。 */
  async function adminEditMaterial(materialId: string, data: { name?: string, categoryId?: string | null, tags?: string[], visibility?: string, deptIds?: string[] }) {
    try {
      await api.adminUpdate(materialId, data)
      toast.success('物料信息已更新')
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  /**
   * 代传新版本：版本记录的上传人记当前操作者。
   * syncDetail=true（详情页）刷新详情/版本列表/预览；=false（管理页）刷新管理列表。
   */
  async function adminUploadNewVersion(materialId: string, fileId: string, filename: string, note: string, syncDetail = true) {
    try {
      const result = await api.adminNewVersion(materialId, { fileId, filename, note: note || undefined })
      toast.success(`已上传 v${result.material.currentVersion}`)
      if (syncDetail) {
        detail.value = result
        versions.value = await api.myVersions(materialId)
        await loadPreview(materialId)
      }
      else {
        await loadAdmin()
      }
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  // ---------- 管理端：批量操作（逐项容错，toast 汇总成功/失败） ----------

  function toastBatchResult(result: BatchResult, action: string) {
    if (result.failures.length) {
      const reasons = result.failures.slice(0, 3).map(failure => `${failure.name || failure.id}：${failure.message}`).join('；')
      toast.warning(`${action}完成：成功 ${result.succeeded}/${result.total}，${reasons}${result.failures.length > 3 ? ' 等' : ''}`)
    }
    else {
      toast.success(`已${action} ${result.succeeded} 个物料`)
    }
  }

  async function adminBatchCategory(ids: string[], categoryId: string) {
    try {
      const result = await api.adminBatchCategory(ids, categoryId)
      toastBatchResult(result, '移动分组')
      await loadAdmin()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function adminBatchTags(ids: string[], tags: string[], mode: 'APPEND' | 'REPLACE') {
    try {
      const result = await api.adminBatchTags(ids, tags, mode)
      toastBatchResult(result, mode === 'APPEND' ? '追加标签' : '覆盖标签')
      await loadAdmin()
      void loadTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function adminBatchStatus(ids: string[], status: 'ACTIVE' | 'ARCHIVED') {
    try {
      const result = await api.adminBatchStatus(ids, status)
      toastBatchResult(result, status === 'ARCHIVED' ? '归档' : '恢复')
      await loadAdmin()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function adminBatchDelete(ids: string[]) {
    try {
      const result = await api.adminBatchDelete(ids)
      toastBatchResult(result, '删除')
      clampPage(adminPager, adminList.value.length - result.succeeded)
      await loadAdmin()
    }
    catch (error) {
      toast.error(errorMessage(error))
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
    myDeptOptions,
    adminDeptOptions,
    hasManage,
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
    importFolder,
    editMaterial,
    isOwner,
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
    loadMyDepartments,
    loadAdminDepartments,
    loadAdmin,
    applyAdminFilters,
    adminSetStatus,
    adminRemove,
    adminDownload,
    adminPreview,
    adminEditMaterial,
    adminUploadNewVersion,
    adminBatchCategory,
    adminBatchTags,
    adminBatchStatus,
    adminBatchDelete,
    saveCategory,
    removeCategory,
  })
  // sdk 保持原引用不进 reactive 代理，避免宿主注入对象被包装
  return Object.assign(model, { sdk })
}

export type MaterialPluginModel = ReturnType<typeof useMaterialPlugin>
