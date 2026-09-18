import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  BundleDoc,
  Capabilities,
  CustomPageDoc,
  HomeCard,
  HomeConfig,
  ManifestPayload,
  NavigationConfigItem,
  NavigationPageInfo,
  PackDoc,
  PackVersionDoc,
  ServerView,
  ThemeProfile,
} from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createYmclApi } from '../api/ymcl-api'
import { formatTime, openYmcl as openYmclProtocol, siteOrigin, ymclAddSiteUri } from './ymcl-protocol'

const MAX_BUNDLE_SIZE = 20 * 1024 * 1024

export function useYmclAdapter(sdk: YuDreamPluginSdk) {
  const api = createYmclApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)

  // 与 SPI PluginPrincipal.hasPermission 语义一致：permissions 含 "*" 即超管通配
  const hasPermission = (code: string) =>
    sdk.account.permissions.includes('*') || sdk.account.permissions.includes(code)
  const canPublish = computed(() => hasPermission('plugin:ymcl-adapter:publish'))
  const canDesign = computed(() => hasPermission('plugin:ymcl-adapter:design'))

  const capabilities = ref<Capabilities | null>(null)
  const manifest = ref<ManifestPayload | null>(null)
  const packs = ref<PackDoc[]>([])
  const servers = ref<ServerView[]>([])
  const bundles = ref<BundleDoc[]>([])

  const origin = siteOrigin()
  const addSiteUri = ymclAddSiteUri(origin)

  function errorMessage(error: unknown) {
    if (error && typeof error === 'object') {
      // 宿主拦截器 reject 的是 AxiosError：真实原因在 response.data.message
      const data = error as { response?: { data?: { message?: string } }, message?: string }
      return data.response?.data?.message || data.message || '请求失败'
    }
    return '请求失败'
  }

  async function run(task: () => Promise<void>) {
    loading.value = true
    try {
      await task()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function copy(value: string, message = '已复制') {
    try {
      await navigator.clipboard.writeText(value)
      toast.success(message)
    }
    catch {
      toast.error('复制失败，请手动复制')
    }
  }

  /** 域 Logo / 主题背景：上传到宿主公开文件，并拼成启动器可拉取的绝对地址。 */
  function toPublicAbsoluteUrl(raw: string) {
    if (!raw) {
      return ''
    }
    if (/^https?:\/\//i.test(raw)) {
      return raw
    }
    const resolved = sdk.files.assetUrl(raw)
    if (/^https?:\/\//i.test(resolved)) {
      return resolved
    }
    return `${origin}${resolved.startsWith('/') ? '' : '/'}${resolved}`
  }

  async function uploadImage(file: File) {
    if (!sdk.files?.uploadImage) {
      throw new Error('当前宿主未提供文件上传能力')
    }
    const uploaded = await sdk.files.uploadImage(file, {
      module: 'ymcl-adapter',
      publicAccess: true,
    })
    return toPublicAbsoluteUrl(uploaded.assetUrl || uploaded.url || '')
  }

  async function loadOverview() {
    await run(async () => {
      const [caps, manifestPayload] = await Promise.all([api.capabilities(), api.manifest()])
      capabilities.value = caps
      manifest.value = manifestPayload
      if (!caps.domain?.name && manifest.value) {
        capabilities.value = {
          ...caps,
          domain: { ...caps.domain, name: origin },
        }
      }
    })
  }

  // ── 域身份配置 ────────────────────────────────────────────────────────

  const domainForm = reactive<{ name: string, description: string, logo_url: string }>({ name: '', description: '', logo_url: '' })

  async function loadDomainConfig() {
    await run(async () => {
      const config = await api.domainConfig()
      domainForm.name = config.name || ''
      domainForm.description = config.description || ''
      domainForm.logo_url = config.logo_url || ''
    })
  }

  async function saveDomainConfig() {
    saving.value = true
    try {
      await api.saveDomainConfig({
        name: domainForm.name.trim(),
        description: domainForm.description.trim(),
        logo_url: domainForm.logo_url.trim(),
      })
      toast.success('域身份已保存')
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

  // ── 整合包 ────────────────────────────────────────────────────────────

  const packVersions = ref<PackVersionDoc['manifest'] & { version?: string, releasedAt?: string } | null>(null)
  const activePack = ref<PackDoc | null>(null)

  async function loadPacks() {
    await run(async () => {
      const result = await api.packs()
      packs.value = result.packs || []
    })
  }

  async function updatePack(packId: string, data: { name: string, description: string, icon: string, defaultChannel: string }) {
    saving.value = true
    try {
      await api.updatePack(packId, data)
      toast.success('整合包信息已保存')
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

  async function deletePack(packId: string) {
    saving.value = true
    try {
      const result = await api.deletePack(packId)
      toast.success(`已删除整合包 ${packId}（${result.versions} 个版本、解绑 ${result.unboundServers} 台服务器）`)
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

  async function deletePackVersion(packId: string, version: string) {
    saving.value = true
    try {
      await api.deletePackVersion(packId, version)
      toast.success(`已删除版本 ${version}`)
      const versions = await api.packVersions(packId)
      return versions.versions || []
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      saving.value = false
    }
  }

  async function loadPackVersion(packId: string, version: string) {
    await run(async () => {
      const doc = await api.packVersion(packId, version)
      packVersions.value = {
        ...doc.manifest,
        version: doc.version,
        releasedAt: doc.releasedAt,
      }
    })
  }

  // ── 服务器绑定 ────────────────────────────────────────────────────────

  async function loadServers() {
    await run(async () => {
      const result = await api.servers()
      servers.value = result.servers || []
    })
  }

  async function saveBinding(serverId: string, binding: { packId: string, channel: string, pinnedVersion: string | null, updatePolicy: string }) {
    saving.value = true
    try {
      await api.saveBinding(serverId, binding)
      toast.success(`服务器 ${serverId} 绑定已更新`)
      await loadServers()
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

  // ── 首页布局 ──────────────────────────────────────────────────────────

  const homeForm = reactive<{ locked: boolean, cards: HomeCard[], updatedAt: string }>({ locked: false, cards: [], updatedAt: '' })
  const homeDirty = ref(false)

  async function loadHome() {
    await run(async () => {
      const config = await api.homeConfig()
      homeForm.locked = Boolean(config.locked)
      homeForm.cards = (config.cards || []).map(card => ({ ...card }))
      homeForm.updatedAt = config.updatedAt || ''
      homeDirty.value = false
    })
  }

  async function saveHome() {
    saving.value = true
    try {
      const config: HomeConfig = {
        schemaVersion: 1,
        locked: homeForm.locked,
        cards: homeForm.cards,
      }
      await api.saveHomeConfig(config)
      toast.success('首页布局已保存，成员下次拉取清单即生效')
      homeDirty.value = false
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

  /** 清除首页托管：空布局 = 启动器不托管，成员使用启动器默认首页。 */
  async function clearHome() {
    saving.value = true
    try {
      await api.saveHomeConfig({ schemaVersion: 1, locked: false, cards: [] })
      toast.success('已清除首页托管，成员将使用启动器默认首页')
      homeForm.cards = []
      homeForm.locked = false
      homeForm.updatedAt = ''
      homeDirty.value = false
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

  function moveCard(index: number, offset: -1 | 1) {
    const target = index + offset
    if (target < 0 || target >= homeForm.cards.length) {
      return
    }
    const cards = [...homeForm.cards]
    const [card] = cards.splice(index, 1)
    cards.splice(target, 0, card)
    homeForm.cards = cards
    homeDirty.value = true
  }

  function removeCard(index: number) {
    const cards = [...homeForm.cards]
    cards.splice(index, 1)
    homeForm.cards = cards
    homeDirty.value = true
  }

  function addCard(card: HomeCard) {
    homeForm.cards = [...homeForm.cards, card]
    homeDirty.value = true
  }

  function updateCard(index: number, card: HomeCard) {
    const cards = [...homeForm.cards]
    cards[index] = card
    homeForm.cards = cards
    homeDirty.value = true
  }

  // ── 域主题 ────────────────────────────────────────────────────────────

  /**
   * 表单态全部用字符串承载：空串 = 未设置（回退成员个人偏好），布尔用
   * on/off 三态，数字在保存时解析并校验量纲（YAP §6.9：blur 0-40px、
   * opacity 0-100）。extras 保存 GET 到的不认识的字段（如 cssVars），保存时
   * 原样带回，避免管理页编辑丢配置。
   */
  const themeForm = reactive({
    configured: false,
    modeDefault: '',
    accentPreset: '',
    accentHex: '',
    backgroundUrl: '',
    backgroundBlur: '',
    backgroundOpacity: '',
    windowTransparent: '',
    windowOpacity: '',
    windowBlur: '',
    advancedRendering: '',
    pageTransitions: '',
    /** GET 到的服务端主题原文（含 cssVars 等表单外字段），JSON 查看用。 */
    raw: null as ThemeProfile | null,
    extras: {} as Record<string, unknown>,
    updatedAt: '',
  })
  const themeDirty = ref(false)

  function markThemeDirty() {
    themeDirty.value = true
  }

  const ACCENT_PRESET_VALUES = ['pink', 'orange', 'green', 'blue', 'purple', 'system']

  function normalizeAccentHex(value: string) {
    const hex = value.trim().toLowerCase()
    if (!hex)
      return ''
    const withHash = hex.startsWith('#') ? hex : `#${hex}`
    return /^#[0-9a-f]{6}$/.test(withHash) ? withHash : ''
  }

  /** 解析可空整数字段；返回 null=未设置，'invalid'=格式或量纲不合法。 */
  function parseOptionalInt(value: string, min: number, max: number): number | null | 'invalid' {
    if (!value.trim())
      return null
    if (!/^-?\d+$/.test(value.trim()))
      return 'invalid'
    const parsed = Number.parseInt(value, 10)
    return parsed < min || parsed > max ? 'invalid' : parsed
  }

  function triToBool(value: string): boolean | null {
    return value === '' ? null : value === 'on'
  }

  function boolToTri(value: boolean | undefined | null): string {
    return value == null ? '' : (value ? 'on' : 'off')
  }

  async function loadTheme() {
    await run(async () => {
      const view = await api.themeConfig()
      const theme = view.theme || {}
      const accent = String(theme.accentColor?.value || '')
      const accentIsPreset = ACCENT_PRESET_VALUES.includes(accent)
      const accentIsHex = /^#[0-9a-fA-F]{6}$/.test(accent) || accent.startsWith('custom:')
      themeForm.configured = Boolean(view.configured)
      themeForm.raw = theme
      themeForm.modeDefault = theme.mode?.default || ''
      themeForm.accentPreset = accentIsPreset ? accent : (accentIsHex ? 'custom' : '')
      themeForm.accentHex = accent.startsWith('custom:') ? accent.slice('custom:'.length) : (accentIsHex ? accent : '')
      themeForm.backgroundUrl = String(theme.background?.url || '')
      themeForm.backgroundBlur = theme.background?.blur == null ? '' : String(theme.background.blur)
      themeForm.backgroundOpacity = theme.background?.opacity == null ? '' : String(theme.background.opacity)
      themeForm.windowTransparent = boolToTri(theme.window?.transparent)
      themeForm.windowOpacity = theme.window?.opacity == null ? '' : String(theme.window.opacity)
      themeForm.windowBlur = boolToTri(theme.window?.blur)
      themeForm.advancedRendering = boolToTri(theme.advancedRendering?.value)
      themeForm.pageTransitions = boolToTri(theme.pageTransitions?.value)
      const managedKeys = new Set([
        'mode',
        'accentColor',
        'background',
        'window',
        'advancedRendering',
        'pageTransitions',
        'schemaVersion',
        'updatedAt',
      ])
      const extras: Record<string, unknown> = {}
      for (const [key, value] of Object.entries(theme)) {
        if (!managedKeys.has(key))
          extras[key] = value
      }
      themeForm.extras = extras
      themeForm.updatedAt = view.updatedAt || theme.updatedAt || ''
      themeDirty.value = false
    })
  }

  function buildThemePayload(): ThemeProfile | null {
    const blur = parseOptionalInt(themeForm.backgroundBlur, 0, 40)
    if (blur === 'invalid') {
      toast.error('背景模糊需为 0-40 的整数（像素）')
      return null
    }
    const backgroundOpacity = parseOptionalInt(themeForm.backgroundOpacity, 0, 100)
    if (backgroundOpacity === 'invalid') {
      toast.error('背景可见度需为 0-100 的整数（百分比）')
      return null
    }
    const windowOpacity = parseOptionalInt(themeForm.windowOpacity, 0, 100)
    if (windowOpacity === 'invalid') {
      toast.error('界面不透明度需为 0-100 的整数（百分比）')
      return null
    }
    if (themeForm.accentPreset === 'custom' && !normalizeAccentHex(themeForm.accentHex)) {
      toast.error('自定义强调色需为 #rrggbb 十六进制颜色')
      return null
    }

    const accentValue = themeForm.accentPreset === 'custom'
      ? normalizeAccentHex(themeForm.accentHex)
      : themeForm.accentPreset
    const theme: ThemeProfile = {
      ...(themeForm.extras as Record<string, unknown>),
      schemaVersion: 1,
      mode: themeForm.modeDefault
        ? { default: themeForm.modeDefault as 'dark' | 'light' | 'oled' | 'system' }
        : {},
      accentColor: accentValue ? { value: accentValue } : {},
      background: {
        ...(themeForm.backgroundUrl.trim() ? { url: themeForm.backgroundUrl.trim() } : {}),
        ...(blur == null ? {} : { blur }),
        ...(backgroundOpacity == null ? {} : { opacity: backgroundOpacity }),
      },
      window: {
        ...(triToBool(themeForm.windowTransparent) == null ? {} : { transparent: triToBool(themeForm.windowTransparent) as boolean }),
        ...(windowOpacity == null ? {} : { opacity: windowOpacity }),
        ...(triToBool(themeForm.windowBlur) == null ? {} : { blur: triToBool(themeForm.windowBlur) as boolean }),
      },
      advancedRendering: triToBool(themeForm.advancedRendering) == null ? {} : { value: triToBool(themeForm.advancedRendering) as boolean },
      pageTransitions: triToBool(themeForm.pageTransitions) == null ? {} : { value: triToBool(themeForm.pageTransitions) as boolean },
    }
    return theme
  }

  async function saveTheme() {
    const theme = buildThemePayload()
    if (!theme)
      return false
    saving.value = true
    try {
      await api.saveThemeConfig(theme)
      toast.success('域主题已保存；成员启动器经 manifest 更新即时生效')
      themeForm.configured = true
      themeDirty.value = false
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

  async function resetTheme() {
    saving.value = true
    try {
      await api.saveThemeConfig({ reset: true })
      toast.success('已撤销域主题托管，成员恢复各自的外观设置')
      themeForm.configured = false
      themeForm.raw = null
      themeForm.modeDefault = ''
      themeForm.accentPreset = ''
      themeForm.accentHex = ''
      themeForm.backgroundUrl = ''
      themeForm.backgroundBlur = ''
      themeForm.backgroundOpacity = ''
      themeForm.windowTransparent = ''
      themeForm.windowOpacity = ''
      themeForm.windowBlur = ''
      themeForm.advancedRendering = ''
      themeForm.pageTransitions = ''
      themeForm.extras = {}
      themeForm.updatedAt = ''
      themeDirty.value = false
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

  // ── 扩展页面包 ────────────────────────────────────────────────────────

  async function loadBundles() {
    await run(async () => {
      const result = await api.bundles()
      bundles.value = result.bundles || []
    })
  }

  async function uploadBundle(bundleId: string, version: string, file: File) {
    const id = bundleId.trim()
    const ver = version.trim()
    if (!id || !ver) {
      toast.error('请填写包 ID 和版本号')
      return null
    }
    if (file.size > MAX_BUNDLE_SIZE) {
      toast.error('zip 超过 20MB，请精简包体')
      return null
    }
    saving.value = true
    try {
      const result = await api.uploadBundle(id, ver, file)
      toast.success(`已上传 ${id}@${ver}，sha256 已由服务端计算`)
      await loadBundles()
      return result
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      saving.value = false
    }
  }

  // ── 域导航配置 ────────────────────────────────────────────────────────

  const navForm = reactive<{ configured: boolean, items: NavigationConfigItem[], pages: NavigationPageInfo[], updatedAt: string }>({
    configured: false,
    items: [],
    pages: [],
    updatedAt: '',
  })
  const navDirty = ref(false)

  async function loadNavigation() {
    await run(async () => {
      const config = await api.navigationConfig()
      navForm.configured = Boolean(config.configured)
      navForm.items = (config.items || []).map(cloneNavItem)
      navForm.pages = config.pages || []
      navForm.updatedAt = config.updatedAt || ''
      navDirty.value = false
    })
  }

  function cloneNavItem(item: NavigationConfigItem): NavigationConfigItem {
    const kind = item.kind || (item.pageId ? 'page' : 'directory')
    return {
      kind,
      pageId: item.pageId,
      id: item.id,
      title: item.title,
      icon: item.icon,
      enabled: item.enabled !== false,
      sort: item.sort,
      children: (item.children || []).map(cloneNavItem),
    }
  }

  /** 节点稳定 key：directory 用 id，page 用 pageId。 */
  function navNodeKey(item: NavigationConfigItem): string {
    if (item.kind === 'directory') {
      return item.id || `dir-${item.title || 'anon'}`
    }
    return item.pageId || item.id || ''
  }

  function navPageInfo(pageId: string) {
    return navForm.pages.find(page => page.pageId === pageId)
  }

  function findNavNode(key: string, items = navForm.items): NavigationConfigItem | null {
    for (const item of items) {
      if (navNodeKey(item) === key) {
        return item
      }
      const child = findNavNode(key, item.children || [])
      if (child) {
        return child
      }
    }
    return null
  }

  /** 已加入导航树（任意层级）的页面 pageId 集合（不含 directory）。 */
  function usedPageIds(items = navForm.items): Set<string> {
    const used = new Set<string>()
    for (const item of items) {
      if (item.pageId) {
        used.add(item.pageId)
      }
      usedPageIds(item.children || []).forEach(id => used.add(id))
    }
    return used
  }

  async function saveNavigation() {
    saving.value = true
    try {
      await api.saveNavigationConfig(navForm.items)
      toast.success('域导航已保存，成员下次拉取清单即生效')
      navForm.configured = true
      navDirty.value = false
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

  async function resetNavigation() {
    saving.value = true
    try {
      await api.resetNavigationConfig()
      toast.success('已恢复内置默认导航')
      await loadNavigation()
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

  function toggleNavItem(key: string) {
    const item = findNavNode(key)
    if (item) {
      item.enabled = !item.enabled
      navDirty.value = true
    }
  }

  /** 任意深度摘除节点（不标脏；调用方决定后续动作）。 */
  function detachNavItem(key: string, items = navForm.items): NavigationConfigItem | null {
    const index = items.findIndex(entry => navNodeKey(entry) === key)
    if (index >= 0) {
      const [item] = items.splice(index, 1)
      return item || null
    }
    for (const item of items) {
      const found = detachNavItem(key, item.children || [])
      if (found) {
        return found
      }
    }
    return null
  }

  function removeNavItem(key: string) {
    if (detachNavItem(key)) {
      navDirty.value = true
    }
  }

  /** 节点所在的兄弟数组（顶层即 navForm.items），供拖拽按落点插入。 */
  function navSiblingsOf(key: string, items = navForm.items): NavigationConfigItem[] | null {
    if (items.some(entry => navNodeKey(entry) === key)) {
      return items
    }
    for (const item of items) {
      const found = navSiblingsOf(key, item.children || [])
      if (found) {
        return found
      }
    }
    return null
  }

  /** 节点层级（顶层=1）；未找到返回 0。 */
  function navDepthOf(key: string, items = navForm.items, depth = 1): number {
    for (const item of items) {
      if (navNodeKey(item) === key) {
        return depth
      }
      const found = navDepthOf(key, item.children || [], depth + 1)
      if (found) {
        return found
      }
    }
    return 0
  }

  /** 子树高度（叶子=1），拖拽深度上限校验用。 */
  function navSubtreeHeight(item: NavigationConfigItem): number {
    const children = item.children || []
    if (!children.length) {
      return 1
    }
    return 1 + Math.max(...children.map(navSubtreeHeight))
  }

  /** 子树是否包含 key（防止拖进自己的后代形成环）。 */
  function navSubtreeContains(item: NavigationConfigItem, key: string): boolean {
    if (navNodeKey(item) === key) {
      return true
    }
    return (item.children || []).some(child => navSubtreeContains(child, key))
  }

  function updateNavItem(key: string, patch: { title?: string, icon?: string }) {
    const item = findNavNode(key)
    if (!item) {
      return
    }
    if (patch.title !== undefined) {
      const title = patch.title.trim()
      if (title) {
        item.title = title
      }
      else {
        delete item.title
      }
    }
    if (patch.icon !== undefined) {
      const icon = patch.icon.trim()
      if (icon) {
        item.icon = icon
      }
      else {
        delete item.icon
      }
    }
    navDirty.value = true
  }

  function createDirectoryNode(title: string, icon?: string): NavigationConfigItem {
    return {
      kind: 'directory',
      id: `dir-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
      title: title.trim() || '新目录',
      icon: icon?.trim() || 'i-ri:folder-line',
      enabled: true,
      sort: 0,
      children: [],
    }
  }

  function addDirectoryItem(title: string, parentKey?: string | null, icon?: string) {
    const node = createDirectoryNode(title, icon)
    if (parentKey) {
      const parent = findNavNode(parentKey)
      if (parent) {
        parent.children = [...(parent.children || []), node]
        navDirty.value = true
      }
      return
    }
    navForm.items = [...navForm.items, node]
    navDirty.value = true
  }

  /** 添加页面节点：parentKey 为空挂顶层，否则挂为该节点子项（任意深度）。 */
  function addPageNavItem(pageId: string, parentKey?: string | null) {
    const node: NavigationConfigItem = {
      kind: 'page',
      pageId,
      enabled: true,
      sort: 0,
      children: [],
    }
    if (parentKey) {
      const parent = findNavNode(parentKey)
      if (!parent) {
        return
      }
      node.sort = (parent.children?.length || 0) * 10
      parent.children = [...(parent.children || []), node]
      navDirty.value = true
      return
    }
    node.sort = navForm.items.length * 10
    navForm.items = [...navForm.items, node]
    navDirty.value = true
  }

  // ── 自定义页面（remoteEntry 页面注册） ───────────────────────────────

  const customPages = ref<CustomPageDoc[]>([])

  async function loadCustomPages() {
    await run(async () => {
      const result = await api.customPages()
      customPages.value = result.pages || []
    })
  }

  /** 仅刷新页面注册表：自定义页面增删后调用，避免覆盖未保存的导航树编辑。 */
  async function refreshNavPages() {
    try {
      const config = await api.navigationConfig()
      navForm.pages = config.pages || []
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function saveCustomPage(data: Partial<CustomPageDoc>) {
    saving.value = true
    try {
      const result = await api.saveCustomPage(data)
      toast.success(`页面 ${result.id} 已保存，加入导航后随清单下发`)
      await Promise.all([loadCustomPages(), refreshNavPages()])
      return result.id
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      saving.value = false
    }
  }

  async function deleteCustomPage(id: string) {
    saving.value = true
    try {
      await api.deleteCustomPage(id)
      toast.success(`已删除页面 ${id}；导航中残留引用已标记失效，保存导航后移除`)
      await Promise.all([loadCustomPages(), refreshNavPages()])
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

  function downloadBundleUrl(bundleId: string, version: string) {
    return sdk.http.url(`/v1/bundles/${encodeURIComponent(bundleId)}/${encodeURIComponent(version)}/package.zip`)
  }

  function openYmcl() {
    const ok = openYmclProtocol(origin)
    if (ok) {
      toast.info('已尝试唤起 YMCL；若拉起的是旧启动器，请重新构建并安装 YMCL-Axolotl（登记 ymcl://）')
    }
  }

  return reactive({
    loading,
    saving,
    canPublish,
    canDesign,
    capabilities,
    manifest,
    packs,
    packVersions,
    activePack,
    servers,
    bundles,
    origin,
    addSiteUri,
    domainForm,
    homeForm,
    homeDirty,
    navForm,
    navDirty,
    themeForm,
    themeDirty,
    markThemeDirty,
    navPageInfo,
    usedPageIds,
    formatTime,
    copy,
    uploadImage,
    rawGet: api.rawGet,
    rawPut: api.rawPut,
    apiUrl: (path: string) => sdk.http.url(path),
    loadOverview,
    loadDomainConfig,
    saveDomainConfig,
    loadPacks,
    updatePack,
    deletePack,
    deletePackVersion,
    loadPackVersion,
    loadServers,
    saveBinding,
    loadHome,
    saveHome,
    clearHome,
    moveCard,
    removeCard,
    addCard,
    updateCard,
    loadNavigation,
    saveNavigation,
    resetNavigation,
    loadTheme,
    saveTheme,
    resetTheme,
    navNodeKey,
    findNavNode,
    toggleNavItem,
    detachNavItem,
    removeNavItem,
    navSiblingsOf,
    navDepthOf,
    navSubtreeHeight,
    navSubtreeContains,
    updateNavItem,
    addDirectoryItem,
    addPageNavItem,
    customPages,
    loadCustomPages,
    refreshNavPages,
    saveCustomPage,
    deleteCustomPage,
    loadBundles,
    uploadBundle,
    downloadBundleUrl,
    openYmcl,
  })
}

export type YmclAdapterModel = ReturnType<typeof useYmclAdapter>
