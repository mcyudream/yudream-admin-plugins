import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { LauncherPack, LauncherPackDetail, PackForm, YmclChromeForm, YmclChromePage, YmclNavNode } from '../types'
import { useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createLauncherApi } from '../api/launcher-api'
import { applyYmclDragPayload, formatTime, openYmcl as openYmclProtocol, siteOrigin, ymclAddSiteUri, ymclSiteToken } from './ymcl-protocol'

export function useLauncherPlugin(sdk: YuDreamPluginSdk) {
  const api = createLauncherApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const packs = ref<LauncherPack[]>([])
  const packDetail = ref<LauncherPackDetail | null>(null)
  const packPager = reactive({ page: 1, size: 10, total: 0 })
  const packKeyword = ref('')
  const packForm = reactive<PackForm>({ packId: '', name: '', description: '', icon: '' })
  const chromePages = ref<YmclChromePage[]>([])
  const chromeForm = reactive<YmclChromeForm>({
    displayName: '',
    logoUrl: '',
    backgroundUrl: '',
    navTree: [],
  })

  const origin = siteOrigin()
  const addSiteUri = ymclAddSiteUri(origin)
  const siteToken = ymclSiteToken(origin)

  function errorMessage(error: unknown) {
    if (error && typeof error === 'object') {
      const data = error as { response?: { data?: { message?: string } }, message?: string }
      return data.response?.data?.message || data.message || '请求失败'
    }
    return '请求失败'
  }

  async function run(task: () => Promise<void>, silent = false) {
    loading.value = true
    try {
      await task()
    }
    catch (error) {
      if (!silent) {
        toast.error(errorMessage(error))
      }
    }
    finally {
      loading.value = false
    }
  }

  async function loadPacks() {
    await run(async () => {
      const result = await api.packs(packKeyword.value.trim(), packPager.page, packPager.size)
      packs.value = result.records || []
      packPager.total = Number(result.total ?? 0)
      const maxPage = Math.max(1, Math.ceil(packPager.total / packPager.size) || 1)
      if (packPager.page > maxPage) {
        packPager.page = maxPage
        const next = await api.packs(packKeyword.value.trim(), packPager.page, packPager.size)
        packs.value = next.records || []
        packPager.total = Number(next.total ?? 0)
      }
    })
  }

  function applyPackFilters() {
    packPager.page = 1
    return loadPacks()
  }

  function resetPackForm() {
    packForm.packId = ''
    packForm.name = ''
    packForm.description = ''
    packForm.icon = ''
  }

  async function createPack() {
    const packId = packForm.packId.trim()
    const name = packForm.name.trim()
    if (!packId || !name) {
      toast.error('请填写整合包 ID 和名称')
      return false
    }
    saving.value = true
    try {
      await api.createPack({
        packId,
        name,
        description: packForm.description.trim(),
        icon: packForm.icon.trim(),
      })
      toast.success('整合包已创建')
      resetPackForm()
      await loadPacks()
      return true
    }
    catch (error) {
      toast.error(errorMessage(error))
      return false
    }
    finally {
      saving.value = false
    }
  }

  async function loadPackDetail(packId: string) {
    await run(async () => {
      packDetail.value = await api.pack(packId)
    })
  }

  async function rollback(packId: string, targetVersionId: string) {
    saving.value = true
    try {
      await api.rollback(packId, targetVersionId)
      toast.success(`已回滚到 ${targetVersionId}`)
      await Promise.all([loadPacks(), loadPackDetail(packId)])
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  async function copy(value: string, message = '已复制') {
    await navigator.clipboard.writeText(value)
    toast.success(message)
  }

  function cloneNavNode(node: Partial<YmclNavNode>, root: boolean): YmclNavNode {
    return {
      code: node.code || '',
      kind: root ? 'tab' : 'menu',
      title: node.title || '',
      icon: node.icon || '',
      pageCode: node.pageCode || '',
      visible: node.visible !== false,
      children: (node.children || []).map(child => cloneNavNode(child, false)),
    }
  }

  function applyChrome(admin: { chrome?: Partial<YmclChromeForm>, pages?: YmclChromePage[] }) {
    chromePages.value = admin.pages || []
    const chrome = admin.chrome || {}
    chromeForm.displayName = chrome.displayName || ''
    chromeForm.logoUrl = chrome.logoUrl || ''
    chromeForm.backgroundUrl = chrome.backgroundUrl || ''
    chromeForm.navTree = (chrome.navTree || []).map(node => cloneNavNode(node, true))
  }

  async function loadChrome() {
    await run(async () => {
      applyChrome(await api.chrome())
    })
  }

  async function saveChrome() {
    saving.value = true
    try {
      applyChrome(await api.saveChrome({
        displayName: chromeForm.displayName.trim(),
        logoUrl: chromeForm.logoUrl.trim(),
        backgroundUrl: chromeForm.backgroundUrl.trim(),
        navTree: chromeForm.navTree,
      }))
      toast.success('启动器外观已保存，YMCL 下次拉清单即生效')
      return true
    }
    catch (error) {
      toast.error(errorMessage(error))
      return false
    }
    finally {
      saving.value = false
    }
  }

  async function uploadChromeImage(file: File) {
    const uploaded = await sdk.files.uploadImage(file, { module: 'launcher-adapter', publicAccess: true })
    return uploaded.assetUrl || uploaded.url || ''
  }

  function openYmcl() {
    openYmclProtocol(origin)
  }

  function applyDragPayload(event: DragEvent) {
    applyYmclDragPayload(event, origin)
  }

  return reactive({
    loading,
    saving,
    packs,
    packDetail,
    packPager,
    packKeyword,
    packForm,
    chromePages,
    chromeForm,
    origin,
    addSiteUri,
    siteToken,
    formatTime,
    loadPacks,
    applyPackFilters,
    resetPackForm,
    createPack,
    loadPackDetail,
    rollback,
    loadChrome,
    saveChrome,
    uploadChromeImage,
    copy,
    openYmcl,
    applyDragPayload,
  })
}

export type LauncherPluginModel = ReturnType<typeof useLauncherPlugin>
