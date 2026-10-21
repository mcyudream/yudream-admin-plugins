import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { ref } from 'vue'

/** 公开活动（匿名接口，只含面向全体部门且已发布/已结束的活动）：插件公开页与站点主题共用。 */
export interface PublicActivity {
  id: string
  title: string
  summary?: string
  description?: string
  coverUrl?: string
  signupStart?: number | string
  signupEnd?: number | string
  activityStart?: number | string
  activityEnd?: number | string
  status: string
  statusKey?: string
  statusText?: string
  meta?: string
}

async function getJson<T>(sdk: YuDreamPluginSdk, path: string): Promise<T | null> {
  const response = await fetch(sdk.site.assetUrl(`/api/plugins/minecraft-activity-proof${path}`), { credentials: 'omit', headers: { Accept: 'application/json' } })
  if (!response.ok) {
    return null
  }
  const body = await response.json() as { code?: number, data?: T } | T
  if (body && typeof body === 'object' && 'code' in body) {
    return Number((body as { code?: number }).code) === 200 ? ((body as { data?: T }).data ?? null) : null
  }
  return body as T
}

export function usePublicActivities(sdk: YuDreamPluginSdk) {
  const loading = ref(false)
  const activities = ref<PublicActivity[]>([])
  const activity = ref<PublicActivity | null>(null)

  async function loadList() {
    loading.value = true
    try {
      activities.value = (await getJson<{ items: PublicActivity[] }>(sdk, '/public/activities?size=60'))?.items ?? []
    }
    finally {
      loading.value = false
    }
  }

  async function loadOne(id: string) {
    loading.value = true
    try {
      activity.value = id ? await getJson<PublicActivity>(sdk, `/public/activities/${encodeURIComponent(id)}`) : null
    }
    finally {
      loading.value = false
    }
  }

  return { loading, activities, activity, loadList, loadOne }
}
