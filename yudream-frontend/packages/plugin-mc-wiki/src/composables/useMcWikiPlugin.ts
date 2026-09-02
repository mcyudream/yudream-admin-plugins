import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { WikiItem, WikiJob, WikiMeta, WikiRecipe, WikiRenderMeta, WikiVersionRow } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createMcWikiApi } from '../api/mc-wiki-api'

export function useMcWikiPlugin(sdk: YuDreamPluginSdk) {
  const api = createMcWikiApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)
  const meta = ref<WikiMeta | null>(null)
  const renderMeta = ref<WikiRenderMeta | null>(null)
  const versions = ref<WikiVersionRow[]>([])
  const jobs = ref<WikiJob[]>([])
  const recipes = ref<WikiRecipe[]>([])
  const items = ref<WikiItem[]>([])

  const versionPager = reactive({ page: 1, size: 20, total: 0 })
  const jobPager = reactive({ page: 1, size: 10, total: 0 })
  const recipePager = reactive({ page: 1, size: 24, total: 0 })
  const itemPager = reactive({ page: 1, size: 24, total: 0 })
  const versionFilters = reactive({ keyword: '', type: '', status: '' })
  const recipeKeyword = ref('')
  const itemKeyword = ref('')
  /** 公开页查看的已发布版本；空串表示当前默认版本 */
  const publicVersion = ref('')
  /** 渲染资产生成标识（updatedAt），作为图标 URL 的破缓存参数，渲染资产更新后图标地址随之变化 */
  const renderGen = computed(() => renderMeta.value?.updated && renderMeta.value.updatedAt ? String(renderMeta.value.updatedAt) : '')

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

  async function loadMeta() {
    meta.value = await api.meta()
  }

  async function loadRenders() {
    renderMeta.value = await api.renders()
  }

  async function loadVersions() {
    loading.value = true
    try {
      const page = await api.versions(versionPager.page, versionPager.size, versionFilters.keyword, versionFilters.type, versionFilters.status)
      versions.value = page.records
      versionPager.total = page.total
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyVersionFilters() {
    versionPager.page = 1
    return loadVersions()
  }

  async function loadJobs() {
    loading.value = true
    try {
      const page = await api.jobs(jobPager.page, jobPager.size)
      jobs.value = page.records
      jobPager.total = page.total
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function loadRecipes() {
    loading.value = true
    try {
      const page = await api.publicRecipes(recipeKeyword.value, recipePager.page, recipePager.size, publicVersion.value)
      recipes.value = page.records
      recipePager.total = page.total
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function searchRecipes() {
    recipePager.page = 1
    return loadRecipes()
  }

  async function loadItems() {
    loading.value = true
    try {
      const page = await api.publicItems(itemKeyword.value, itemPager.page, itemPager.size, publicVersion.value)
      items.value = page.records
      itemPager.total = page.total
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function searchItems() {
    itemPager.page = 1
    return loadItems()
  }

  /** 切换公开页查看的已发布版本并重置分页 */
  function changePublicVersion(version: string) {
    publicVersion.value = version
    itemPager.page = 1
    recipePager.page = 1
    return Promise.all([loadItems(), loadRecipes()])
  }

  async function refreshVersions() {
    loading.value = true
    try {
      await api.refreshVersions()
      versionPager.page = 1
      await loadVersions()
      toast.success('官方版本清单已刷新')
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function publishVersion(version: string) {
    try {
      await api.publishVersion(version)
      toast.success(`${version} 已发布到公开百科`)
      await loadVersions()
      await loadMeta()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function unpublishVersion(version: string) {
    try {
      await api.unpublishVersion(version)
      toast.success(`${version} 已从公开百科取消发布`)
      if (publicVersion.value === version) {
        publicVersion.value = ''
      }
      await loadVersions()
      await loadMeta()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function deleteVersionData(version: string) {
    try {
      const removed = await api.deleteVersionData(version)
      toast.success(`已删除 ${version} 的 ${removed.items} 个物品、${removed.recipes} 个配方与 ${removed.files} 个贴图文件`)
      if (!clampPage(versionPager, versions.value.length - 1)) {
        // 当前页已空，回退一页后由 loadVersions 重新拉取
      }
      await loadVersions()
      await loadMeta()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function deleteJob(job: WikiJob) {
    try {
      await api.deleteJob(job.jobId)
      toast.success('导入任务已删除')
      clampPage(jobPager, jobs.value.length - 1)
      await loadJobs()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  function iconUrl(itemId: string, size?: number) {
    return api.iconUrl(itemId, size, publicVersion.value, renderGen.value)
  }

  return reactive({
    api,
    loading,
    meta,
    renderMeta,
    versions,
    jobs,
    recipes,
    items,
    versionPager,
    jobPager,
    recipePager,
    itemPager,
    versionFilters,
    recipeKeyword,
    itemKeyword,
    publicVersion,
    renderGen,
    loadMeta,
    loadRenders,
    loadVersions,
    applyVersionFilters,
    loadJobs,
    loadRecipes,
    searchRecipes,
    loadItems,
    searchItems,
    changePublicVersion,
    refreshVersions,
    publishVersion,
    unpublishVersion,
    deleteVersionData,
    deleteJob,
    iconUrl,
  })
}

export type McWikiPluginModel = ReturnType<typeof useMcWikiPlugin>
