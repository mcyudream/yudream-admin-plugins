import type { Activity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage } from './utils'

export function useAdminActivities(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const actingId = ref('')
  const activities = ref<Activity[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })
  const filters = reactive({ keyword: '', status: '' })

  async function load() {
    loading.value = true
    try {
      const result = await api.admin.activities({ keyword: filters.keyword, status: filters.status, page: pager.page, size: pager.size })
      activities.value = result.records
      pager.total = Number(result.total) || 0
    }
    finally {
      loading.value = false
    }
  }

  async function search() {
    pager.page = 1
    await load()
  }

  async function resetFilters() {
    filters.keyword = ''
    filters.status = ''
    pager.page = 1
    await load()
  }

  function replaceRow(next: Activity) {
    activities.value = activities.value.map(item => item.id === next.id ? next : item)
  }

  async function publish(row: Activity) {
    if (actingId.value) {
      return
    }
    actingId.value = row.id
    try {
      replaceRow(await api.admin.publishActivity(row.id))
      toast.success('活动已发布')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      actingId.value = ''
    }
  }

  function close(row: Activity) {
    if (actingId.value) {
      return
    }
    modal.confirm({
      title: '结束活动',
      content: `确认结束「${row.title}」吗？结束后用户将无法报名或取消，仍可核验与导出证明。`,
      onConfirm: async () => {
        actingId.value = row.id
        try {
          replaceRow(await api.admin.closeActivity(row.id))
          toast.success('活动已结束')
        }
        catch (error) {
          toast.warning(errorMessage(error))
        }
        finally {
          actingId.value = ''
        }
      },
    })
  }

  function remove(row: Activity) {
    if (actingId.value) {
      return
    }
    modal.confirm({
      title: '删除活动',
      content: `确认删除活动「${row.title}」吗？参与记录、答题进度和该活动的证明导出将一并删除，且不可恢复。`,
      onConfirm: async () => {
        actingId.value = row.id
        try {
          await api.admin.deleteActivity(row.id)
          toast.success('活动已删除')
          if (activities.value.length === 1 && pager.page > 1) {
            pager.page -= 1
          }
          await load()
        }
        catch (error) {
          toast.warning(errorMessage(error))
        }
        finally {
          actingId.value = ''
        }
      },
    })
  }

  return {
    loading,
    actingId,
    activities,
    pager,
    filters,
    load,
    search,
    resetFilters,
    publish,
    close,
    remove,
  }
}
