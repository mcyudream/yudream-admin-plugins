import type { TableSortingState } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionAuditLog } from '../types'
import { computed, onMounted, reactive, ref } from 'vue'
import { useUnionApiShell } from './useUnionApiShell'

export function useUnionAudit(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const records = ref<UnionAuditLog[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)
  const keyword = ref('')
  const sortBy = ref('at')
  const order = ref<'asc' | 'desc'>('desc')

  /** 服务端排序的受控状态：列头点击 → changeSorting → 重拉第一页 */
  const sorting = computed<TableSortingState>(() => [{ id: sortBy.value, desc: order.value === 'desc' }])

  async function load() {
    await run(async () => {
      const body = await api.pageAudit(page.value, size.value, keyword.value.trim() || undefined, sortBy.value, order.value)
      records.value = body.records ?? []
      total.value = Number(body.total ?? 0)
    })
  }

  function search() {
    page.value = 1
    return run(load)
  }

  function changeSorting(next: TableSortingState) {
    const first = next[0]
    if (first) {
      sortBy.value = first.id
      order.value = first.desc ? 'desc' : 'asc'
    }
    else {
      sortBy.value = 'at'
      order.value = 'desc'
    }
    page.value = 1
    return run(load)
  }

  function changePage(next: number, nextSize = size.value) {
    page.value = next
    size.value = nextSize
    return run(load)
  }

  function deleteOne(log: UnionAuditLog) {
    return run(async () => {
      if (!log.id) return
      await api.deleteAudit(log.id)
      toast.success('审计记录已删除')
      await load()
    })
  }

  function clear() {
    return run(async () => {
      await api.clearAudit()
      toast.success('审计记录已清空')
      page.value = 1
      await load()
    })
  }

  onMounted(load)

  return reactive({ loading, error, records, page, size, total, keyword, sorting, load, search, changeSorting, changePage, deleteOne, clear })
}
