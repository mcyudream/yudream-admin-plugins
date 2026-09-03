import type { MyParticipation } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage, resolveFileUrl } from './utils'

export function useMyActivities(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const actingId = ref('')
  const participations = ref<MyParticipation[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })

  async function load() {
    loading.value = true
    try {
      const result = await api.me.participations(pager.page, pager.size)
      participations.value = result.records
      pager.total = Number(result.total) || 0
    }
    finally {
      loading.value = false
    }
  }

  function replaceRow(next: MyParticipation) {
    participations.value = participations.value.map(item => item.activityId === next.activityId ? next : item)
  }

  async function verify(row: MyParticipation) {
    if (actingId.value) {
      return
    }
    actingId.value = row.activityId
    try {
      const next = await api.me.verify(row.activityId)
      replaceRow(next)
      if (next.verifyStatus === 'PASSED') {
        toast.success('核验通过')
      }
      else {
        toast.warning(next.verifyNote || '暂未达成核验条件')
      }
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      actingId.value = ''
    }
  }

  function cancel(row: MyParticipation) {
    if (actingId.value) {
      return
    }
    modal.confirm({
      title: '取消参与',
      content: `确认取消参与「${row.title}」吗？取消后需要重新报名才能再次参与。`,
      onConfirm: async () => {
        actingId.value = row.activityId
        try {
          await api.me.cancel(row.activityId)
          toast.success('已取消参与')
          if (participations.value.length === 1 && pager.page > 1) {
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

  function coverOf(row: MyParticipation) {
    return resolveFileUrl(sdk, row.coverUrl)
  }

  return {
    loading,
    actingId,
    participations,
    pager,
    load,
    verify,
    cancel,
    coverOf,
  }
}
