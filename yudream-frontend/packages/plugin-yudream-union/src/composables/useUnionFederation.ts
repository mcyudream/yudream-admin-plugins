import type { TableSortingState } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FederationStats, UnionJoinEvent } from '../types'
import { computed, onMounted, reactive, ref } from 'vue'
import { useUnionApiShell } from './useUnionApiShell'

export function useUnionFederation(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const stats = ref<FederationStats>({})
  const events = ref<UnionJoinEvent[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)
  const filterSite = ref('')
  const filterName = ref('')
  const sortBy = ref('recordedAt')
  const order = ref<'asc' | 'desc'>('desc')

  /** 服务端排序的受控状态：列头点击 → changeSorting → 重拉第一页 */
  const sorting = computed<TableSortingState>(() => [{ id: sortBy.value, desc: order.value === 'desc' }])

  async function load() {
    await run(async () => {
      const [statsBody, logBody] = await Promise.all([
        api.federationStats(),
        api.federationLog(
          page.value,
          size.value,
          filterSite.value.trim() || undefined,
          filterName.value.trim() || undefined,
          sortBy.value,
          order.value,
        ),
      ])
      stats.value = statsBody
      events.value = logBody.records ?? []
      total.value = Number(logBody.total ?? 0)
    })
  }

  function filter() {
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
      sortBy.value = 'recordedAt'
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

  /** 一键封禁：确认后按规范化角色名写本站联合封禁（来源标注为断言签发站）。 */
  function quickBan(event: UnionJoinEvent) {
    return run(async () => {
      await api.addBan({
        name: event.profileName,
        reason: `跨站登录一键封禁（来源 ${event.siteCode}，serverId ${event.serverId.slice(0, 12)}…）`,
        evidence: `jti=${event.id}`,
      })
      toast.success(`已封禁「${event.profileName}」`)
      await load()
    })
  }

  onMounted(load)

  return reactive({
    loading, error, stats, events, page, size, total, filterSite, filterName, sorting,
    load, filter, changeSorting, changePage, quickBan,
  })
}
