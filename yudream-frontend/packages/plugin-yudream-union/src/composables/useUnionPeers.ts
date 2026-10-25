import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionPeer } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { errorMessageOf, useUnionApiShell } from './useUnionApiShell'

export function useUnionPeers(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const peers = ref<UnionPeer[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)

  const saving = ref(false)
  const enrollOpen = ref(false)
  const enrollError = ref('')
  const issuedToken = ref('')
  const enrollForm = reactive({ peerOrigin: '', enrollToken: '', note: '' })

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
      toast.info('已签发邀请码（5 分钟内有效，仅显示一次）：交给对端管理员，对方凭码把他们的站注册进来')
    })
  }

  function openEnroll() {
    enrollError.value = ''
    enrollOpen.value = true
  }

  /**
   * 严格邀请码模式：凭对端签发的邀请码把本站注册进对端受信目录（后端代理对端机器面）。
   * 失败保持弹窗打开并把错误就近展示在弹窗内：页面级 alert 会被弹窗遮挡，等于无反馈。
   */
  async function enroll() {
    enrollError.value = ''
    if (!enrollForm.peerOrigin.trim() || !enrollForm.enrollToken.trim()) {
      enrollError.value = '对端 origin 与对端签发的邀请码均为必填'
      return
    }
    loading.value = true
    try {
      const body = await api.selfEnrollPeer({
        peerOrigin: enrollForm.peerOrigin.trim(),
        enrollToken: enrollForm.enrollToken.trim(),
        note: enrollForm.note.trim() || undefined,
      })
      enrollOpen.value = false
      Object.assign(enrollForm, { peerOrigin: '', enrollToken: '', note: '' })
      issuedToken.value = ''
      const target = body.peerSiteCode || body.peerSiteName || '对端'
      toast.success(body.peerSecret
        ? `已把本站注册进 ${target} 的受信目录（对端推送密钥仅本次显示：${body.peerSecret}，可选保存用于主动推送封禁）`
        : `已把本站注册进 ${target} 的受信目录`)
      await load()
    }
    catch (cause) {
      enrollError.value = errorMessageOf(cause)
    }
    finally {
      loading.value = false
    }
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
    saving, enrollOpen, enrollError, issuedToken, enrollForm,
    load, issueToken, openEnroll, enroll, toggle, refreshPeer, selfTest, remove, changePage,
  })
}
