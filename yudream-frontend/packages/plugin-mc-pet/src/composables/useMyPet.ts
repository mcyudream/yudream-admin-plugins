import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MyPet, MyPetSavePayload, PetOptions, PetPreferenceMode } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createMcPetApi } from '../api/mc-pet-api'

export function textureUrl(hash?: string) {
  return hash ? `/api/plugins/yudream-skin/textures/${encodeURIComponent(hash)}` : ''
}

export function useMyPet(sdk: YuDreamPluginSdk) {
  const api = createMcPetApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)
  const saving = ref(false)
  const pet = ref<MyPet | null>(null)
  const options = ref<PetOptions>({ skinAvailable: false, players: [], closet: [] })

  /** 表单态：与后端合并语义对齐，未触碰字段不落盘 */
  const form = ref<{
    mode: PetPreferenceMode
    playerName: string
    closetItemId: string
    size: number
    corner: string
    hidden: boolean
  }>({
    mode: 'default',
    playerName: '',
    closetItemId: '',
    size: 120,
    corner: 'bottom-right',
    hidden: false,
  })

  const previewSkin = computed(() => {
    if (form.value.mode === 'player') {
      const player = options.value.players.find(item => item.name === form.value.playerName)
      return textureUrl(player?.textureHash)
    }
    if (form.value.mode === 'closet') {
      const item = options.value.closet.find(entry => entry.id === form.value.closetItemId)
      return textureUrl(item?.textureHash)
    }
    return pet.value?.source === 'texture' && pet.value.skinUrl ? pet.value.skinUrl : sdk.assets.url('assets/steve.png')
  })

  const previewSlim = computed(() => {
    if (form.value.mode === 'player') {
      return options.value.players.find(item => item.name === form.value.playerName)?.model === 'slim'
    }
    return pet.value?.model === 'slim'
  })

  const hasPosition = computed(() => pet.value?.positionX != null && pet.value?.positionY != null)

  onMounted(load)

  async function load() {
    loading.value = true
    try {
      const [myPet, myOptions] = await Promise.all([api.me.pet(), api.me.options()])
      pet.value = myPet
      options.value = myOptions
      form.value = {
        mode: myPet.preferenceMode,
        playerName: myPet.playerName || '',
        closetItemId: myPet.closetItemId || '',
        size: myPet.size,
        corner: myPet.corner,
        hidden: myPet.hidden,
      }
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载宠物配置失败'))
    }
    finally {
      loading.value = false
    }
  }

  /** 仅刷新选择器数据（如上传皮肤后），不重置表单 */
  async function refreshOptions() {
    try {
      options.value = await api.me.options()
    }
    catch {
      // 刷新失败保留旧数据
    }
  }

  async function save() {
    if (saving.value) {
      return
    }
    if (form.value.mode === 'player' && !form.value.playerName) {
      toast.warning('请选择角色')
      return
    }
    if (form.value.mode === 'closet' && !form.value.closetItemId) {
      toast.warning('请选择衣柜皮肤')
      return
    }
    saving.value = true
    try {
      const payload: MyPetSavePayload = {
        mode: form.value.mode,
        playerName: form.value.mode === 'player' ? form.value.playerName : undefined,
        closetItemId: form.value.mode === 'closet' ? form.value.closetItemId : undefined,
        size: form.value.size,
        corner: form.value.corner as MyPet['corner'],
        hidden: form.value.hidden,
      }
      pet.value = await api.me.savePet(payload)
      toast.success('宠物设置已保存')
    }
    catch (error) {
      toast.warning(errorMessage(error, '保存失败'))
    }
    finally {
      saving.value = false
    }
  }

  async function clearPosition() {
    if (saving.value) {
      return
    }
    saving.value = true
    try {
      pet.value = await api.me.savePet({ clearPosition: true })
      toast.success('已回到默认停靠角')
    }
    catch (error) {
      toast.warning(errorMessage(error, '操作失败'))
    }
    finally {
      saving.value = false
    }
  }

  async function setHidden(hidden: boolean) {
    form.value.hidden = hidden
    if (saving.value) {
      return
    }
    saving.value = true
    try {
      pet.value = await api.me.savePet({ hidden })
    }
    catch (error) {
      toast.warning(errorMessage(error, '操作失败'))
    }
    finally {
      saving.value = false
    }
  }

  return {
    loading,
    saving,
    pet,
    options,
    form,
    previewSkin,
    previewSlim,
    hasPosition,
    load,
    refreshOptions,
    save,
    clearPosition,
    setHidden,
  }
}

export function errorMessage(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback
}
