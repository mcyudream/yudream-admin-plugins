import type { UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { resolveFileUrl, resolveThumbUrl, errorMessage } from './utils'

export function useSquare(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useYdToast()
  const loading = ref(false)
  const activities = ref<UserActivity[]>([])
  const pager = reactive({ page: 1, size: 12, total: 0 })

  async function load() {
    loading.value = true
    try {
      const result = await api.me.activities(pager.page, pager.size)
      activities.value = result.records
      pager.total = Number(result.total) || 0
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载活动广场失败'))
    }
    finally {
      loading.value = false
    }
  }

  function coverOf(activity: UserActivity) {
    return resolveFileUrl(sdk, activity.coverUrl)
  }

  function coverThumbOf(activity: UserActivity) {
    return resolveThumbUrl(sdk, activity.coverUrl)
  }

  return {
    loading,
    activities,
    pager,
    load,
    coverOf,
    coverThumbOf,
  }
}
