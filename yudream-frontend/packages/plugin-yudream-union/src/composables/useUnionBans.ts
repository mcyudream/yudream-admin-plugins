import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionBan } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { useUnionApiShell } from './useUnionApiShell'

export function useUnionBans(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const bans = ref<UnionBan[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)

  const saving = ref(false)
  const addOpen = ref(false)
  const addForm = reactive({ name: '', reason: '', evidence: '' })

  async function load() {
    await run(async () => {
      const body = await api.pageBans(page.value, size.value)
      bans.value = body.records ?? []
      total.value = Number(body.total ?? 0)
    })
  }

  function add() {
    return run(async () => {
      await api.addBan({ name: addForm.name.trim(), reason: addForm.reason.trim(), evidence: addForm.evidence.trim() || undefined })
      addOpen.value = false
      Object.assign(addForm, { name: '', reason: '', evidence: '' })
      toast.success('联合封禁已生效')
      await load()
    })
  }

  function lift(ban: UnionBan) {
    return run(async () => {
      await api.liftBan(ban.nameLower ?? ban.name ?? '')
      toast.success(`已解除「${ban.name}」的联合封禁`)
      await load()
    })
  }

  function pull() {
    return run(async () => {
      const body = await api.pullBans()
      toast.success(`账本拉取完成，应用 ${body.applied} 条`)
      await load()
    })
  }

  function changePage(next: number, nextSize = size.value) {
    page.value = next
    size.value = nextSize
    return run(load)
  }

  onMounted(load)

  return reactive({
    loading, error, bans, page, size, total,
    saving, addOpen, addForm,
    load, add, lift, pull, changePage,
  })
}
