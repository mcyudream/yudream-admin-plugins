import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetAdminItem, PetClickAction, PetClosetOption, PetCorner, PetDefaults, PetDefaultsMode } from '../types'
import { useFaModal, useFaToast } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createMcPetApi } from '../api/mc-pet-api'
import { errorMessage } from './useMyPet'

/** 管理端全局默认设置。 */
export function usePetDefaults(sdk: YuDreamPluginSdk) {
  const api = createMcPetApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)
  const saving = ref(false)
  /** 管理员自己的衣柜（走用户端 /me 接口，仅用于挑选/上传默认皮肤） */
  const closet = ref<PetClosetOption[]>([])
  const skinAvailable = ref(false)
  const form = ref<{
    mode: PetDefaultsMode
    playerName: string
    textureHash: string
    model: 'classic' | 'slim'
    animation: boolean
    clickAction: PetClickAction
    size: number
    corner: PetCorner
  }>({
    mode: 'builtin',
    playerName: '',
    textureHash: '',
    model: 'classic',
    animation: true,
    clickAction: 'menu',
    size: 120,
    corner: 'bottom-right',
  })

  onMounted(load)
  onMounted(() => {
    void loadCloset()
  })

  function applyDefaults(defaults: PetDefaults) {
    form.value = {
      mode: defaults.mode,
      playerName: defaults.playerName || '',
      textureHash: defaults.textureHash || '',
      model: defaults.model || 'classic',
      animation: defaults.animation,
      clickAction: defaults.clickAction,
      size: defaults.size,
      corner: defaults.corner,
    }
  }

  async function load() {
    loading.value = true
    try {
      applyDefaults(await api.admin.defaults())
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载默认设置失败'))
    }
    finally {
      loading.value = false
    }
  }

  /** 上传/挑选默认皮肤依赖管理员自己的衣柜；失败静默降级，不影响其余设置。 */
  async function loadCloset() {
    try {
      const options = await api.me.options()
      skinAvailable.value = options.skinAvailable
      closet.value = options.closet
    }
    catch {
      skinAvailable.value = false
      closet.value = []
    }
  }

  async function save() {
    if (saving.value) {
      return
    }
    if (form.value.mode === 'player' && !form.value.playerName.trim()) {
      toast.warning('请输入默认角色名')
      return
    }
    if (form.value.mode === 'texture' && !form.value.textureHash) {
      toast.warning('请先上传或从衣柜选择默认皮肤')
      return
    }
    saving.value = true
    try {
      const defaults = await api.admin.saveDefaults({
        mode: form.value.mode,
        playerName: form.value.mode === 'player' ? form.value.playerName.trim() : undefined,
        textureHash: form.value.mode === 'texture' ? form.value.textureHash : undefined,
        model: form.value.model,
        animation: form.value.animation,
        clickAction: form.value.clickAction,
        size: form.value.size,
        corner: form.value.corner,
      })
      applyDefaults(defaults)
      toast.success('默认设置已保存')
    }
    catch (error) {
      toast.warning(errorMessage(error, '保存失败'))
    }
    finally {
      saving.value = false
    }
  }

  return { loading, saving, closet, skinAvailable, form, load, loadCloset, save }
}

/** 管理端用户宠物列表。 */
export function usePetAdmin(sdk: YuDreamPluginSdk) {
  const api = createMcPetApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()

  const loading = ref(false)
  const acting = ref(false)
  const records = ref<PetAdminItem[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })
  const detail = ref<PetAdminItem | null>(null)
  const detailVisible = ref(false)

  onMounted(load)

  async function load() {
    loading.value = true
    try {
      const result = await api.admin.pets(pager.page, pager.size)
      records.value = result.records
      pager.total = Number(result.total) || 0
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载用户宠物列表失败'))
    }
    finally {
      loading.value = false
    }
  }

  async function openDetail(item: PetAdminItem) {
    acting.value = true
    try {
      detail.value = await api.admin.petDetail(item.userId)
      detailVisible.value = true
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载详情失败'))
    }
    finally {
      acting.value = false
    }
  }

  function reset(item: PetAdminItem) {
    modal.confirm({
      title: '重置用户宠物',
      content: `确认重置用户 ${item.userId} 的宠物偏好吗？重置后该用户将回到全局默认配置。`,
      onConfirm: async () => {
        acting.value = true
        try {
          await api.admin.resetPet(item.userId)
          toast.success('已重置该用户的宠物偏好')
          detailVisible.value = false
          if (records.value.length === 1 && pager.page > 1) {
            pager.page -= 1
          }
          await load()
        }
        catch (error) {
          toast.warning(errorMessage(error, '重置失败'))
        }
        finally {
          acting.value = false
        }
      },
    })
  }

  return { loading, acting, records, pager, detail, detailVisible, load, openDetail, reset }
}
