import type { CheckInRewardRecord } from '../types'
import type { PlaytimePointsApi } from '../api/playtime-points-api'
import { reactive, ref } from 'vue'
import { useFaToast } from '@yudream/components'
import { errorMessage } from './utils'

/** 管理端「打卡积分」流水页：分页列表 + 按项目 / 用户筛选。 */
export function useCheckInRewards(api: PlaytimePointsApi) {
  const toast = useFaToast()
  const loading = ref(false)
  const records = ref<CheckInRewardRecord[]>([])
  const projectOptions = ref<{ label: string, value: string }[]>([])
  /** provider 是否可用：不可用时页面顶部给出降级提示。 */
  const providerAvailable = ref(true)
  const pager = reactive({ page: 1, size: 10, total: 0 })
  const filters = reactive({ projectId: '', userId: '' })

  async function loadProjectOptions() {
    try {
      const data = await api.admin.options()
      providerAvailable.value = data.dependencies?.projectProgress !== false
      projectOptions.value = (data.projects ?? []).map(project => ({ label: project.name, value: project.id }))
    }
    catch {
      // 项目选项加载失败只影响下拉筛选项，不阻塞列表
    }
  }

  async function load() {
    loading.value = true
    try {
      const result = await api.admin.checkInRewards({
        projectId: filters.projectId || undefined,
        userId: filters.userId || undefined,
        page: pager.page,
        size: pager.size,
      })
      records.value = result.records
      pager.total = Number(result.total) || 0
    }
    catch (error) {
      toast.error(errorMessage(error))
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
    filters.projectId = ''
    filters.userId = ''
    pager.page = 1
    await load()
  }

  async function onPageChange(page: number) {
    pager.page = page
    await load()
  }

  async function onSizeChange(size: number) {
    pager.size = size
    pager.page = 1
    await load()
  }

  return {
    loading,
    records,
    projectOptions,
    providerAvailable,
    pager,
    filters,
    load,
    loadProjectOptions,
    search,
    resetFilters,
    onPageChange,
    onSizeChange,
  }
}
