import type { MySummary, SettlementRecord } from '../types'
import type { PlaytimePointsApi } from '../api/playtime-points-api'
import { reactive, ref } from 'vue'
import { useFaToast } from '@yudream/components'
import { errorMessage } from './utils'

/** 用户端「我的积分」：汇总数据与个人结算流水。 */
export function useMyPoints(api: PlaytimePointsApi) {
  const toast = useFaToast()
  const loading = ref(false)
  const summary = ref<MySummary | null>(null)
  const records = ref<SettlementRecord[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })

  async function load() {
    loading.value = true
    try {
      const [summaryData, pageData] = await Promise.all([
        api.me.summary(),
        api.me.settlements(pager.page, pager.size),
      ])
      summary.value = summaryData
      records.value = pageData.records
      pager.total = Number(pageData.total) || 0
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
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

  return { loading, summary, records, pager, load, onPageChange, onSizeChange }
}
