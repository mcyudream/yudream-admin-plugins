import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createSkinApi } from '../api/skin-api'
import type { MigrationLogEntry, MigrationReport, MigrationStatus, OfficialBindSettings, OfficialBindStatus, SkinClosetItem, SkinMe, SkinPlayer, SkinSettings, SkinSummary, SkinTexture, SkinUser } from '../types'

export function useSkinPlugin(sdk: YuDreamPluginSdk) {
  const api = createSkinApi(sdk)
  const toast = useYdToast()
  const loading = ref(false)
  const saving = ref('')
  const refreshError = ref('')

  const me = ref<SkinMe | null>(null)
  const summary = ref<SkinSummary>({ users: 0, players: 0, textures: 0, closetItems: 0, options: 0 })
  const settings = ref<SkinSettings>({ maxPlayersPerUser: 3, allowPublicUpload: true, siteNotice: '' })
  const users = ref<SkinUser[]>([])
  const players = ref<SkinPlayer[]>([])
  const textures = ref<SkinTexture[]>([])
  const closetItems = ref<SkinClosetItem[]>([])
  const migrationReport = ref<MigrationReport | null>(null)
  const migrationStatus = ref<MigrationStatus>({ state: 'IDLE', running: false, logs: [] })
  const migrationLogs = ref<MigrationLogEntry[]>([])
  const migrationLogVisible = ref(false)
  const officialBind = ref<OfficialBindStatus>({ settingsEnabled: false, bind: null })
  const officialBindSettings = reactive<OfficialBindSettings>({ enabled: false, clientId: '', clientSecret: '' })
  const migrationStream = ref<AbortController | null>(null)
  const scope = ref<'user' | 'admin'>('user')
  let loadVersion = 0

  const playerForm = reactive({ name: '', ownerId: '' })
  const textureForm = reactive({
    name: '',
    type: 'steve',
    model: 'default',
    contentType: 'image/png',
    base64: '',
    publicAccess: true,
  })
  const closetForm = reactive({ userId: '', textureHash: '', itemName: '' })
  const settingsForm = reactive<SkinSettings>({ maxPlayersPerUser: 3, allowPublicUpload: true, siteNotice: '' })
  const migrationForm = reactive({
    host: 'localhost',
    port: 3306,
    database: 'blessing_skin',
    username: '',
    password: '',
    textureBaseDir: '',
    textureArchiveBase64: '',
    textureArchiveName: '',
  })

  const selectedPlayerName = ref('')
  const selectedTextureHash = ref('')
  const selectedClosetId = ref('')
  const assignForm = reactive({ skinHash: '', capeHash: '' })
  const playerRenameForm = reactive({ name: '' })
  const closetRenameForm = reactive({ itemName: '' })
  const canManage = computed(() => sdk.account.permissions.includes('*') || sdk.account.permissions.includes('plugin:yudream-skin:manage'))
  const canUse = computed(() => {
    return sdk.account.permissions.includes('*') || sdk.account.permissions.includes('plugin:yudream-skin:user')
  })
  const currentUserId = computed(() => me.value?.userId || sdk.account.userId || '')
  const accountName = computed(() => me.value?.hostUser?.nickname || me.value?.hostUser?.username || sdk.account.username || currentUserId.value)
  const defaultPlayerName = computed(() => me.value?.defaultPlayerName || '')

  const textureOptions = computed(() => textures.value.map(item => ({
    label: `${item.name} (${item.hash.slice(0, 10)})`,
    value: item.hash,
  })))

  const closetTextureOptions = computed(() => closetItems.value
    .map((item) => {
      const texture = textures.value.find(texture => texture.hash === item.textureHash)
      return {
        item,
        texture,
        label: `${item.itemName || texture?.name || item.textureHash} (${item.textureHash.slice(0, 10)})`,
        value: item.textureHash,
      }
    }))

  const skinTextureOptions = computed(() => closetTextureOptions.value
    .filter(item => item.texture?.type !== 'cape')
    .map(({ label, value }) => ({ label, value })))

  const capeTextureOptions = computed(() => closetTextureOptions.value
    .filter(item => item.texture?.type === 'cape')
    .map(({ label, value }) => ({ label, value })))

  const userOptions = computed(() => {
    const options = users.value.map(item => ({
      label: `${item.nickname || item.email} (${item.id})`,
      value: item.id,
    }))
    if (options.length || !currentUserId.value) {
      return options
    }
    return [{
      label: `${me.value?.hostUser?.nickname || sdk.account.username || '当前用户'} (${currentUserId.value})`,
      value: currentUserId.value,
    }]
  })

  const selectedPlayer = computed(() => players.value.find(item => item.name === selectedPlayerName.value))
  const selectedTexture = computed(() => textures.value.find(item => item.hash === selectedTextureHash.value))
  const selectedClosetItem = computed(() => closetItems.value.find(item => item.id === selectedClosetId.value))
  const selectedClosetTexture = computed(() => textures.value.find(item => item.hash === selectedClosetItem.value?.textureHash))
  const selectedPlayerSkin = computed(() => textureUrl(selectedPlayer.value?.skinHash))
  const selectedPlayerCape = computed(() => textureUrl(selectedPlayer.value?.capeHash))
  const selectedPlayerSlim = computed(() => textures.value.find(item => item.hash === selectedPlayer.value?.skinHash)?.model === 'slim')
  const selectedTextureSkin = computed(() => selectedTexture.value?.type === 'cape' ? '' : textureUrl(selectedTexture.value?.hash))
  const selectedTextureCape = computed(() => selectedTexture.value?.type === 'cape' ? textureUrl(selectedTexture.value?.hash) : '')
  const selectedTextureSlim = computed(() => selectedTexture.value?.model === 'slim')
  const selectedClosetSkin = computed(() => selectedClosetTexture.value?.type === 'cape' ? '' : textureUrl(selectedClosetTexture.value?.hash))
  const selectedClosetCape = computed(() => selectedClosetTexture.value?.type === 'cape' ? textureUrl(selectedClosetTexture.value?.hash) : '')
  const selectedClosetSlim = computed(() => selectedClosetTexture.value?.model === 'slim')

  async function load(options: {
    includeMigration?: boolean
    includePlayers?: boolean
    includeTextures?: boolean
    includeCloset?: boolean
    scope?: 'user' | 'admin'
  } = {}) {
    const nextScope = options.scope || scope.value
    const includePlayers = options.includePlayers ?? true
    const includeTextures = options.includeTextures ?? true
    const includeCloset = options.includeCloset ?? true
    const currentLoadVersion = ++loadVersion
    const scopeChanged = scope.value !== nextScope
    if (scopeChanged || !includePlayers) {
      players.value = []
      clearSelectedPlayer()
    }
    if (scopeChanged || !includeTextures) {
      textures.value = []
      selectedTextureHash.value = ''
    }
    if (scopeChanged || !includeCloset) {
      closetItems.value = []
      clearSelectedCloset()
    }
    scope.value = nextScope
    loading.value = true
    try {
      const [status, current, textureList, currentMigration] = await Promise.all([
        api.status(),
        canUse.value ? api.me() : Promise.resolve<SkinMe>({
          userId: sdk.account.userId || '',
          permissions: sdk.account.permissions,
        }),
        includeTextures
          ? (nextScope === 'admin' ? api.adminTextures() : api.textures())
          : Promise.resolve(textures.value),
        options.includeMigration ? api.migrationStatus().catch(() => migrationStatus.value) : Promise.resolve(migrationStatus.value),
      ])
      const [playerList, closetList] = await Promise.all([
        includePlayers
          ? (nextScope === 'admin' ? api.adminPlayers() : canUse.value ? api.players() : Promise.resolve([]))
          : Promise.resolve(players.value),
        includeCloset
          ? (nextScope === 'admin' ? api.adminCloset() : canUse.value ? api.closet() : Promise.resolve([]))
          : Promise.resolve(closetItems.value),
      ])
      const [nextOfficialBind, nextOfficialSettings] = await Promise.all([
        nextScope === 'user' && canUse.value
          ? api.officialBindStatus().catch(() => officialBind.value)
          : Promise.resolve(officialBind.value),
        nextScope === 'admin' && canManage.value && options.includeMigration
          ? api.officialBindSettings().catch(() => ({ ...officialBindSettings }))
          : Promise.resolve(officialBindSettings),
      ])
      const [userList, savedSettings] = nextScope === 'admin' && canManage.value
        ? [hostUserOption(current), await api.settings()] as const
          : [hostUserOption(current), status.settings || settings.value] as const
      if (currentLoadVersion !== loadVersion || scope.value !== nextScope) {
        return
      }
      refreshError.value = ''
      me.value = current
      summary.value = status
      users.value = userList
      players.value = playerList
      textures.value = textureList
      closetItems.value = closetList
      officialBind.value = nextOfficialBind
      Object.assign(officialBindSettings, nextOfficialSettings)
      settings.value = savedSettings || status.settings || settings.value
      Object.assign(settingsForm, settings.value)
      applyMigrationStatus(currentMigration)
      if (!playerForm.ownerId && currentUserId.value) {
        playerForm.ownerId = currentUserId.value
      }
      if (!closetForm.userId && currentUserId.value) {
        closetForm.userId = currentUserId.value
      }
      if (includePlayers) {
        const defaultPlayer = current.defaultPlayerName
          ? playerList.find(player => player.name === current.defaultPlayerName)
          : undefined
        const currentSelectedPlayer = selectedPlayerName.value
          ? playerList.find(player => player.name === selectedPlayerName.value)
          : undefined
        if (defaultPlayer && (!selectedPlayerName.value || !currentSelectedPlayer)) {
          selectPlayer(defaultPlayer)
        }
        else if (!currentSelectedPlayer && playerList.length) {
          selectPlayer(defaultPlayer || playerList.find(player => !!player.skinHash || !!player.capeHash) || playerList[0])
        }
        else if (!currentSelectedPlayer) {
          clearSelectedPlayer()
        }
      }
      if (includeTextures) {
        const currentSelectedTexture = selectedTextureHash.value
          ? textureList.find(texture => texture.hash === selectedTextureHash.value)
          : undefined
        if (!currentSelectedTexture && textureList.length) {
          selectTexture(textureList[0])
        }
        else if (!currentSelectedTexture) {
          selectedTextureHash.value = ''
        }
      }
      if (includeCloset) {
        const currentSelectedCloset = selectedClosetId.value
          ? closetList.find(item => item.id === selectedClosetId.value)
          : undefined
        if (!currentSelectedCloset && closetList.length) {
          selectClosetItem(closetList[0])
        }
        else if (!currentSelectedCloset) {
          clearSelectedCloset()
        }
      }
    }
    finally {
      if (currentLoadVersion === loadVersion) {
        loading.value = false
      }
    }
  }

  async function startOfficialBind() {
    const popup = window.open('', 'yudream-official-bind', 'popup,width=640,height=760')
    if (!popup) {
      toast.error('浏览器拦截了授权窗口，请允许弹窗后重试')
      return
    }
    saving.value = 'official-bind'
    try {
      const started = await api.startOfficialBind()
      const complete = async (success: boolean, message?: string) => {
        window.removeEventListener('message', onMessage)
        window.clearTimeout(expiryTimer)
        saving.value = ''
        if (success) {
          toast.success('正版角色已绑定')
          await refreshAfterMutation()
        }
        else {
          toast.error(message || '正版授权失败')
        }
      }
      const onMessage = (event: MessageEvent<{ type?: string, success?: boolean, message?: string }>) => {
        if (event.origin !== window.location.origin || event.data?.type !== 'yudream-official-bind') return
        void complete(Boolean(event.data.success), event.data.message)
      }
      const expiryTimer = window.setTimeout(() => void complete(false, '正版授权已超时，请重新发起'), 10 * 60_000)
      window.addEventListener('message', onMessage)
      popup.location.href = started.authorizeUrl
    }
    catch (error) {
      popup.close()
      saving.value = ''
      toast.error(error instanceof Error ? error.message : '无法发起正版授权')
    }
  }

  async function unbindOfficial() {
    saving.value = 'official-unbind'
    try {
      await api.unbindOfficial()
      officialBind.value = { settingsEnabled: officialBind.value.settingsEnabled, bind: null }
      toast.success('已解除正版角色绑定')
      await refreshAfterMutation()
    }
    catch (error) {
      toast.error(error instanceof Error ? error.message : '解除正版绑定失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function saveOfficialBindSettings() {
    saving.value = 'official-bind-settings'
    try {
      const clientSecret = officialBindSettings.clientSecret?.trim()
      // clientSecret 只写不读：留空 = 保持现有凭据；保存成功后立即清空输入框
      const saved = await api.saveOfficialBindSettings({
        ...officialBindSettings,
        clientSecret: clientSecret || undefined,
      })
      Object.assign(officialBindSettings, saved, { clientSecret: '' })
      toast.success('正版绑定配置已保存')
    }
    catch (error) {
      toast.error(error instanceof Error ? error.message : '正版绑定配置保存失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function createPlayer() {
    if (!playerForm.name) {
      toast.error('请填写角色名')
      return
    }
    saving.value = 'player'
    try {
      const player = scope.value === 'admin' ? await api.createAdminPlayer({ ...playerForm }) : await api.createPlayer({ ...playerForm })
      Object.assign(playerForm, { name: '', ownerId: '' })
      selectPlayer(player)
      toast.success('角色已创建')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '创建角色失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function createAdminPlayer(data: Record<string, unknown>) {
    saving.value = 'admin-player'
    try {
      const player = await api.createAdminPlayer(data)
      toast.success('角色已创建')
      await load()
      return player
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '创建角色失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  function selectPlayer(player: SkinPlayer) {
    selectedPlayerName.value = player.name
    assignForm.skinHash = player.skinHash || ''
    assignForm.capeHash = player.capeHash || ''
    playerRenameForm.name = player.name
  }

  function clearSelectedPlayer() {
    selectedPlayerName.value = ''
    assignForm.skinHash = ''
    assignForm.capeHash = ''
    playerRenameForm.name = ''
  }

  function selectTexture(texture: SkinTexture) {
    selectedTextureHash.value = texture.hash
    if (!closetForm.textureHash) {
      closetForm.textureHash = texture.hash
    }
  }

  function selectClosetItem(item: SkinClosetItem) {
    selectedClosetId.value = item.id
    selectedTextureHash.value = item.textureHash
    closetRenameForm.itemName = item.itemName || textureName(item.textureHash)
  }

  function clearSelectedCloset() {
    selectedClosetId.value = ''
    closetRenameForm.itemName = ''
  }

  async function renamePlayer() {
    if (!selectedPlayerName.value || !playerRenameForm.name) {
      toast.error('请选择角色并填写新名称')
      return
    }
    saving.value = 'player-rename'
    try {
      const player = scope.value === 'admin'
        ? await api.renameAdminPlayer(selectedPlayerName.value, { ...playerRenameForm })
        : await api.renamePlayer(selectedPlayerName.value, { ...playerRenameForm })
      toast.success('角色名称已更新')
      await load()
      selectPlayer(player)
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '角色重命名失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function renameAdminPlayer(name: string, data: Record<string, unknown>) {
    saving.value = `admin-player:${name}`
    try {
      const player = await api.renameAdminPlayer(name, data)
      toast.success('角色名称已更新')
      await load()
      return player
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '角色重命名失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  async function deletePlayer() {
    if (!selectedPlayerName.value) {
      toast.error('请先选择角色')
      return
    }
    saving.value = 'player-delete'
    try {
      if (scope.value === 'admin') {
        await api.deleteAdminPlayer(selectedPlayerName.value)
      }
      else {
        await api.deletePlayer(selectedPlayerName.value)
      }
      selectedPlayerName.value = ''
      toast.success('角色已删除')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '删除角色失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function deleteAdminPlayer(name: string) {
    saving.value = `admin-player:${name}`
    try {
      await api.deleteAdminPlayer(name)
      if (selectedPlayerName.value === name) {
        selectedPlayerName.value = ''
      }
      toast.success('角色已删除')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '删除角色失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function assignTextures() {
    if (!selectedPlayerName.value) {
      toast.error('请先选择角色')
      return
    }
    saving.value = 'assign'
    try {
      if (scope.value === 'admin') {
        await api.assignAdminTextures(selectedPlayerName.value, { ...assignForm })
      }
      else {
        await api.assignTextures(selectedPlayerName.value, { ...assignForm })
      }
      toast.success('材质绑定已保存')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '保存材质绑定失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function assignAdminTextures(name: string, data: Record<string, unknown>) {
    saving.value = `admin-assign:${name}`
    try {
      const player = await api.assignAdminTextures(name, data)
      toast.success('材质绑定已保存')
      await load()
      return player
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '保存材质绑定失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  // 服务端变更已完成时，刷新失败不能触发重复写入或删除。
  async function refreshAfterMutation() {
    refreshError.value = ''
    try { await load() }
    catch (error) {
      refreshError.value = `操作已保存，但数据刷新失败：${error instanceof Error ? error.message : '请重新加载。'}`
    }
  }

  async function retryRefresh() {
    if (loading.value || saving.value) return
    await refreshAfterMutation()
  }

  async function setDefaultPlayer(player = selectedPlayer.value) {
    if (!player) {
      toast.error('请先选择角色')
      return
    }
    saving.value = `default-player:${player.name}`
    try {
      const saved = await api.setDefaultPlayer({ name: player.name })
      if (me.value) {
        me.value.defaultPlayerName = saved.name
      }
      selectPlayer(saved)
      toast.success('默认角色已切换')
      await refreshAfterMutation()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '切换默认角色失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function handleTextureFile(source: Event | File) {
    const file = source instanceof File ? source : (source.target as HTMLInputElement).files?.[0]
    if (!file) {
      return
    }
    textureForm.contentType = file.type || 'image/png'
    textureForm.base64 = await fileToBase64(file)
  }

  async function uploadTexture() {
    if (!textureForm.base64) {
      toast.error('请选择 PNG 文件')
      return
    }
    saving.value = 'texture'
    try {
      const texture = scope.value === 'admin' ? await api.uploadAdminTexture({ ...textureForm }) : await api.uploadTexture({ ...textureForm })
      Object.assign(textureForm, {
        name: '',
        type: 'steve',
        model: 'default',
        contentType: 'image/png',
        base64: '',
        publicAccess: true,
      })
      toast.success('材质已上传')
      await load()
      selectTexture(texture)
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '上传材质失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function uploadAdminTexture(data: Record<string, unknown>) {
    saving.value = 'admin-texture'
    try {
      const texture = await api.uploadAdminTexture(data)
      toast.success('材质已上传')
      await load()
      return texture
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '上传材质失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  async function deleteAdminTexture(hash: string) {
    saving.value = `admin-texture:${hash}`
    try {
      await api.deleteAdminTexture(hash)
      if (selectedTextureHash.value === hash) {
        selectedTextureHash.value = ''
      }
      toast.success('材质已删除')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '删除材质失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function updateAdminTexture(hash: string, data: { name: string, publicAccess: boolean }) {
    saving.value = `admin-texture:${hash}`
    try {
      const texture = await api.updateAdminTexture(hash, data)
      toast.success('材质信息已更新')
      await load({ includeCloset: false, includePlayers: false, includeTextures: true, scope: 'admin' })
      selectTexture(texture)
      return texture
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '更新材质信息失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  async function updateOwnTexture(hash: string, data: { name: string, publicAccess: boolean }) {
    saving.value = `texture:${hash}`
    try {
      const texture = await api.updateMyTexture(hash, data)
      toast.success('材质信息已更新')
      await load({ includeCloset: false, includePlayers: false, includeTextures: true, scope: 'user' })
      selectTexture(texture)
      return texture
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '更新材质信息失败')
      return undefined
    }
    finally {
      saving.value = ''
    }
  }

  async function deleteOwnTexture(hash: string) {
    saving.value = `texture:${hash}`
    try {
      await api.deleteMyTexture(hash)
      if (selectedTextureHash.value === hash) {
        selectedTextureHash.value = ''
      }
      toast.success('材质已删除')
      await load({ includeCloset: true, includePlayers: true, includeTextures: true, scope: 'user' })
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '删除材质失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function saveClosetItem() {
    if (!closetForm.textureHash) {
      toast.error('请选择要加入衣柜的材质')
      return
    }
    saving.value = 'closet'
    try {
      const item = scope.value === 'admin' ? await api.saveAdminClosetItem({ ...closetForm }) : await api.saveClosetItem({ ...closetForm })
      Object.assign(closetForm, { userId: '', textureHash: '', itemName: '' })
      toast.success('衣柜项已保存')
      await load()
      selectClosetItem(item)
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '保存衣柜项失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function addTextureToCloset(texture: SkinTexture) {
    closetForm.textureHash = texture.hash
    closetForm.itemName = texture.name
    await saveClosetItem()
  }

  async function applyTextureToSelectedPlayer(texture: SkinTexture | undefined, collect: boolean) {
    if (!texture) {
      toast.error('请先选择材质')
      return
    }
    const player = selectedPlayer.value
    if (!player) {
      toast.error('请先选择角色')
      return
    }
    if (saving.value) {
      return
    }
    const targetScope = scope.value
    const outfit = {
      skinHash: texture.type === 'cape' ? player.skinHash || '' : texture.hash,
      capeHash: texture.type === 'cape' ? texture.hash : player.capeHash || '',
    }
    saving.value = `use-texture:${texture.hash}`
    try {
      const alreadyCollected = closetItems.value.some(item => item.textureHash === texture.hash
        && (targetScope === 'user' || item.userId === currentUserId.value))
      if (collect && !alreadyCollected) {
        const saveItem = targetScope === 'admin' ? api.saveAdminClosetItem : api.saveClosetItem
        await saveItem({
          ...(targetScope === 'admin' ? { userId: currentUserId.value } : {}),
          textureHash: texture.hash,
          itemName: texture.name,
        })
      }
      const saved = targetScope === 'admin'
        ? await api.assignAdminTextures(player.name, outfit)
        : await api.assignTextures(player.name, outfit)
      if (scope.value === targetScope) {
        players.value = players.value.map(item => item.uuid === player.uuid ? saved : item)
      }
      toast.success(collect ? '已保存到衣柜并应用到当前角色' : `外观已应用到 ${player.name}`)
      await refreshAfterMutation()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '应用外观失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function useTextureOnSelectedPlayer(texture = selectedTexture.value) {
    await applyTextureToSelectedPlayer(texture, true)
  }

  async function useClosetItemOnSelectedPlayer(item = selectedClosetItem.value) {
    if (!item) {
      toast.error('请先选择衣柜项')
      return
    }
    const texture = textures.value.find(texture => texture.hash === item.textureHash)
    if (!texture) {
      toast.error('材质信息不存在')
      return
    }
    await applyTextureToSelectedPlayer(texture, false)
  }

  async function renameClosetItem(item = selectedClosetItem.value) {
    if (!item || !closetRenameForm.itemName) {
      toast.error('请选择衣柜项并填写名称')
      return
    }
    saving.value = `closet-rename:${item.id}`
    try {
      const saved = scope.value === 'admin'
        ? await api.renameAdminClosetItem(item.id, { ...closetRenameForm })
        : await api.renameClosetItem(item.id, { ...closetRenameForm })
      closetItems.value = closetItems.value.map(current => current.id === item.id ? saved : current)
      selectClosetItem(saved)
      toast.success('衣柜显示名称已更新')
      await refreshAfterMutation()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '衣柜项重命名失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function deleteClosetItem(item: SkinClosetItem) {
    saving.value = `closet:${item.id}`
    try {
      if (scope.value === 'admin') {
        await api.deleteAdminClosetItem(item.id)
      }
      else {
        await api.deleteClosetItem(item.id)
      }
      if (selectedClosetId.value === item.id) {
        selectedClosetId.value = ''
      }
      closetItems.value = closetItems.value.filter(current => current.id !== item.id)
      if (!selectedClosetId.value && closetItems.value[0]) selectClosetItem(closetItems.value[0])
      toast.success('衣柜项已删除')
      await refreshAfterMutation()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '删除衣柜项失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function saveSettings() {
    saving.value = 'settings'
    try {
      settings.value = await api.saveSettings({ ...settingsForm })
      Object.assign(settingsForm, settings.value)
      toast.success('皮肤站配置已保存')
      await load()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '保存皮肤站配置失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function runMigration() {
    if (!migrationForm.host || !migrationForm.username) {
      toast.error('请填写数据库主机和账号')
      return
    }
    saving.value = 'migration'
    try {
      applyMigrationStatus(await api.migrate({ ...migrationForm }))
      toast.success('迁移已开始')
      openMigrationLog()
      connectMigrationEvents()
    }
    catch (error) {
      toast.error(error instanceof Error && error.message ? error.message : '启动迁移失败')
    }
    finally {
      saving.value = ''
    }
  }

  async function handleMigrationArchive(source: Event | File) {
    const input = source instanceof File ? null : source.target as HTMLInputElement
    const file = source instanceof File ? source : input?.files?.[0]
    if (!file) {
      return
    }
    if (!file.name.toLowerCase().endsWith('.zip')) {
      toast.error('请选择 zip 压缩包')
      if (input) {
        input.value = ''
      }
      return
    }
    migrationForm.textureArchiveBase64 = await fileToBase64(file)
    migrationForm.textureArchiveName = file.name
    toast.success('材质压缩包已选择')
  }

  function openMigrationLog() {
    migrationLogVisible.value = true
    if (migrationStatus.value.running) {
      connectMigrationEvents()
    }
  }

  function connectMigrationEvents() {
    migrationStream.value?.abort()
    const controller = new AbortController()
    migrationStream.value = controller
    void readMigrationEvents(controller)
  }

  async function readMigrationEvents(controller: AbortController) {
    try {
      const headers: Record<string, string> = {
        Accept: 'text/event-stream',
        'Accept-Language': 'zh-CN',
      }
      const token = localStorage.getItem('token')
      if (token) {
        headers.Authorization = token
      }
      const response = await fetch(api.migrationEventsUrl(), {
        headers,
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        throw new Error(`迁移日志连接失败：${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        const chunks = buffer.split(/\r?\n\r?\n/)
        buffer = chunks.pop() || ''
        chunks.forEach(handleMigrationEventChunk)
      }
      if (buffer.trim()) {
        handleMigrationEventChunk(buffer)
      }
    }
    catch (error) {
      if (!controller.signal.aborted) {
        toast.error(error instanceof Error ? error.message : '迁移日志连接失败')
      }
    }
    finally {
      if (migrationStream.value === controller) {
        migrationStream.value = null
      }
    }
  }

  function handleMigrationEventChunk(chunk: string) {
    let eventName = 'message'
    const dataLines: string[] = []
    chunk.split(/\r?\n/).forEach((line) => {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      }
      else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    })
    if (!dataLines.length) {
      return
    }
    const payload = JSON.parse(dataLines.join('\n'))
    if (eventName === 'migration.status') {
      applyMigrationStatus(payload)
    }
    else if (eventName === 'migration.log') {
      appendMigrationLog(payload)
    }
  }

  function applyMigrationStatus(status?: MigrationStatus) {
    if (!status) {
      return
    }
    migrationStatus.value = status
    migrationReport.value = status.report || null
    migrationLogs.value = status.logs || []
  }

  function appendMigrationLog(log: MigrationLogEntry) {
    const exists = migrationLogs.value.some(item => item.time === log.time && item.message === log.message)
    if (!exists) {
      migrationLogs.value.push(log)
    }
  }

  function hostUserOption(current: SkinMe): SkinUser[] {
    if (!current.hostUser) {
      return []
    }
    return [{
      id: String(current.hostUser.id),
      email: current.hostUser.email || '',
      nickname: current.hostUser.nickname || current.hostUser.username || String(current.hostUser.id),
    }]
  }

  function textureUrl(hash?: string) {
    return api.textureUrl(hash)
  }

  function textureName(hash?: string) {
    if (!hash) {
      return '-'
    }
    return textures.value.find(item => item.hash === hash)?.name || hash
  }

  function userName(userId?: string) {
    if (!userId) {
      return '-'
    }
    if (userId === currentUserId.value) {
      return accountName.value
    }
    const user = users.value.find(item => item.id === userId)
    return user ? `${user.nickname || user.email}` : userId
  }

  function dateText(value?: number | string | number[] | Date | null) {
    const timestamp = toTimestamp(value)
    return timestamp > 0 ? new Date(timestamp).toLocaleString() : '-'
  }

  function toTimestamp(value?: number | string | number[] | Date | null): number {
    if (value == null || value === '') {
      return 0
    }
    if (value instanceof Date) {
      return value.getTime()
    }
    if (Array.isArray(value)) {
      const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value
      return new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000)).getTime()
    }
    if (typeof value === 'number') {
      return value > 0 ? (value < 10000000000 ? value * 1000 : value) : 0
    }
    const text = value.trim()
    if (!text) {
      return 0
    }
    const numeric = Number(text)
    if (Number.isFinite(numeric)) {
      return numeric > 0 ? (numeric < 10000000000 ? numeric * 1000 : numeric) : 0
    }
    const parsed = Date.parse(text)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
  }

  async function fileToBase64(file: File) {
    const buffer = await file.arrayBuffer()
    let binary = ''
    const bytes = new Uint8Array(buffer)
    bytes.forEach((byte) => {
      binary += String.fromCharCode(byte)
    })
    return btoa(binary)
  }

  return reactive({
    loading,
    saving,
    refreshError,
    retryRefresh,
    me,
    summary,
    settings,
    users,
    players,
    textures,
    closetItems,
    migrationReport,
    migrationStatus,
    migrationLogs,
    migrationLogVisible,
    officialBind,
    officialBindSettings,
    scope,
    playerForm,
    textureForm,
    closetForm,
    settingsForm,
    migrationForm,
    selectedPlayerName,
    selectedTextureHash,
    selectedClosetId,
    assignForm,
    playerRenameForm,
    closetRenameForm,
    canManage,
    canUse,
    currentUserId,
    accountName,
    defaultPlayerName,
    selectedPlayer,
    selectedTexture,
    selectedClosetItem,
    selectedClosetTexture,
    selectedPlayerSkin,
    selectedPlayerCape,
    selectedPlayerSlim,
    selectedTextureSkin,
    selectedTextureCape,
    selectedTextureSlim,
    selectedClosetSkin,
    selectedClosetCape,
    selectedClosetSlim,
    textureOptions,
    skinTextureOptions,
    capeTextureOptions,
    closetTextureOptions,
    userOptions,
    load,
    startOfficialBind,
    unbindOfficial,
    saveOfficialBindSettings,
    createPlayer,
    createAdminPlayer,
    selectPlayer,
    selectTexture,
    selectClosetItem,
    renamePlayer,
    renameAdminPlayer,
    deletePlayer,
    deleteAdminPlayer,
    assignTextures,
    assignAdminTextures,
    setDefaultPlayer,
    handleTextureFile,
    uploadTexture,
    uploadAdminTexture,
    deleteAdminTexture,
    updateAdminTexture,
    updateOwnTexture,
    deleteOwnTexture,
    saveClosetItem,
    addTextureToCloset,
    useTextureOnSelectedPlayer,
    useClosetItemOnSelectedPlayer,
    renameClosetItem,
    deleteClosetItem,
    saveSettings,
    runMigration,
    handleMigrationArchive,
    openMigrationLog,
    textureUrl,
    textureName,
    userName,
    dateText,
  })
}

export type SkinPluginModel = ReturnType<typeof useSkinPlugin>
