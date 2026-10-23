import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionPeer } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { useUnionApiShell } from './useUnionApiShell'

export function useUnionPeers(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const peers = ref<UnionPeer[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)

  const saving = ref(false)
  const enrollOpen = ref(false)
  const issuedToken = ref('')
  const enrollForm = reactive({ origin: '', enrollToken: '', note: '' })

  async function load() {
    await run(async () => {
      const body = await api.pagePeers(page.value, size.value)
      peers.value = body.records ?? []
      total.value = Number(body.total ?? 0)
    })
  }

  function issueToken() {
    return run(async () => {
      const body = await api.issueEnrollToken()
      issuedToken.value = body.enrollToken
      toast.info('已签发一次性注册令牌（5 分钟内有效，仅显示一次）')
    })
  }

  function enroll() {
    return run(async () => {
      const body = await api.enrollPeer({
        origin: enrollForm.origin.trim(),
        enrollToken: enrollForm.enrollToken.trim(),
        note: enrollForm.note.trim() || undefined,
      })
      enrollOpen.value = false
      Object.assign(enrollForm, { origin: '', enrollToken: '', note: '' })
      issuedToken.value = ''
      toast.success(body.peerSecret
        ? `已注册受信站点（对端推送密钥仅本次显示：${body.peerSecret}）`
        : '已注册受信站点')
      await load()
    })
  }

  function toggle(peer: UnionPeer) {
    return run(async () => {
      await api.setPeerStatus(peer.id, peer.status === 'blocked' ? 'trusted' : 'blocked')
      toast.success(peer.status === 'blocked' ? '已恢复受信' : '已停用')
      await load()
    })
  }

  function refreshPeer(peer: UnionPeer) {
    return run(async () => {
      await api.refreshPeer(peer.id)
      toast.success(`已刷新 ${peer.id} 的公钥与元数据`)
      await load()
    })
  }

  function selfTest(peer: UnionPeer) {
    return run(async () => {
      const body = await api.selfTestPeer(peer.id)
      toast[body.ok ? 'success' : 'error'](`${peer.id}：${body.detail}`)
    })
  }

  function remove(peer: UnionPeer) {
    return run(async () => {
      await api.deletePeer(peer.id)
      toast.success(`已删除 ${peer.id}`)
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
    loading, error, peers, page, size, total,
    saving, enrollOpen, issuedToken, enrollForm,
    load, issueToken, enroll, toggle, refreshPeer, selfTest, remove, changePage,
  })
}
