import type { SettlementRecord } from '../types'
import type { PlaytimePointsApi } from '../api/playtime-points-api'
import { reactive, ref } from 'vue'
import { useFaModal, useFaToast } from '@yudream/components'
import { errorMessage } from './utils'

/** 管理端结算记录页：分页列表、筛选与手动立即结算。 */
export function useSettlements(api: PlaytimePointsApi) {
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const running = ref(false)
  const records = ref<SettlementRecord[]>([])
  const serverOptions = ref<{ label: string, value: string }[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })
  const filters = reactive({ serverId: '', keyword: '' })

  async function loadServerOptions() {
    try {
      const data = await api.admin.options()
      serverOptions.value = data.servers.map(server => ({ label: server.name, value: server.id }))
    }
    catch {
      // 服务器选项加载失败只影响下拉筛选项，不阻塞列表
    }
  }

  async function load() {
    loading.value = true
    try {
      const result = await api.admin.settlements({
        serverId: filters.serverId || undefined,
        keyword: filters.keyword || undefined,
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
    filters.serverId = ''
    filters.keyword = ''
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

  function runNow() {
    modal.confirm({
      title: '立即结算',
      content: '将立即扫描各服务器的玩家在线记录并对已退出的会话结算一次。继续吗？',
      onConfirm: async () => {
        running.value = true
        try {
          const result = await api.admin.run()
          const checkInText = result.checkInRewards
            ? `；打卡积分发放 ${result.checkInRewards.credited} 笔` +
              (result.checkInRewards.retried > 0 ? `（${result.checkInRewards.retried} 笔待重试）` : '')
            : ''
          if (result.message && result.message !== 'OK') {
            toast.warning(`本轮结算 ${result.sessions} 笔（入账 ${result.credited} 笔）：${result.message}${checkInText}`)
          }
          else {
            toast.success(`本轮结算 ${result.sessions} 笔，入账 ${result.credited} 笔${checkInText}`)
          }
          await load()
        }
        catch (error) {
          toast.error(errorMessage(error))
        }
        finally {
          running.value = false
        }
      },
    })
  }

  return { loading, running, records, serverOptions, pager, filters, load, loadServerOptions, search, resetFilters, onPageChange, onSizeChange, runNow }
}
