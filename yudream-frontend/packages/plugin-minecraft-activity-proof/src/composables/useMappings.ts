import type { ActivityProofMapping, ActivityProofServer } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage } from './utils'

export function useMappings(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const saving = ref(false)
  const mappings = ref<ActivityProofMapping[]>([])
  const servers = ref<ActivityProofServer[]>([])
  const minecraftReady = ref(false)
  const pager = reactive({ page: 1, size: 10, total: 0 })
  const selectedServerId = ref('')
  const createForm = reactive({
    playerId: '',
    playerName: '',
    studentNo: '',
  })

  async function load() {
    loading.value = true
    try {
      const status = await api.admin.status()
      minecraftReady.value = status.dependencies.minecraftReady
      if (minecraftReady.value) {
        servers.value = await api.admin.servers()
      }
      await loadMappings()
    }
    finally {
      loading.value = false
    }
  }

  async function loadMappings() {
    const result = await api.admin.mappings(selectedServerId.value, pager.page, pager.size)
    mappings.value = result.records
    pager.total = Number(result.total) || 0
  }

  async function changeServer() {
    pager.page = 1
    await loadMappings()
  }

  async function create() {
    if (!selectedServerId.value) {
      toast.warning('请先选择服务器')
      return
    }
    if (!createForm.playerId.trim() || !createForm.studentNo.trim()) {
      toast.warning('请填写玩家 ID 与学号')
      return
    }
    saving.value = true
    try {
      await api.admin.saveMapping({
        serverId: selectedServerId.value,
        playerId: createForm.playerId.trim(),
        playerName: createForm.playerName.trim(),
        studentNo: createForm.studentNo.trim(),
      })
      createForm.playerId = ''
      createForm.playerName = ''
      createForm.studentNo = ''
      toast.success('映射已保存')
      await loadMappings()
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  function remove(row: ActivityProofMapping) {
    modal.confirm({
      title: '删除映射',
      content: `确认删除「${row.playerName || row.playerId}」的学生映射吗？`,
      onConfirm: async () => {
        saving.value = true
        try {
          await api.admin.deleteMapping(row.id)
          toast.success('映射已删除')
          if (mappings.value.length === 1 && pager.page > 1) {
            pager.page -= 1
          }
          await loadMappings()
        }
        catch (error) {
          toast.warning(errorMessage(error))
        }
        finally {
          saving.value = false
        }
      },
    })
  }

  return {
    loading,
    saving,
    mappings,
    servers,
    minecraftReady,
    pager,
    selectedServerId,
    createForm,
    load,
    loadMappings,
    changeServer,
    create,
    remove,
  }
}
