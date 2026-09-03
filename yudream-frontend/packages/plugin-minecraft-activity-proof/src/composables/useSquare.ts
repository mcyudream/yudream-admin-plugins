import type { UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'

export function useSquare(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
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
    finally {
      loading.value = false
    }
  }

  function coverOf(activity: UserActivity) {
    return sdk.files.assetUrl(activity.coverUrl || undefined)
  }

  return {
    loading,
    activities,
    pager,
    load,
    coverOf,
  }
}
