import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McInteropPeer, McInteropPeerForm, McInteropPeerPull, McInteropSettings } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { useMcInteropShell } from './useMcInteropShell'

export function useMcInteropSite(sdk: YuDreamPluginSdk) {
  const { api, loading, error, run, runStrict } = useMcInteropShell(sdk)

  const settings = ref<McInteropSettings>({})
  const peers = ref<McInteropPeer[]>([])
  const peerOpen = ref(false)
  const peerForm = reactive<McInteropPeerForm>({ siteId: '', name: '', description: '', domain: '', manifestUrl: '' })

  async function load() {
    await run(async () => {
      settings.value = await api.settings()
      const body = await api.peers()
      peers.value = body.records ?? []
    })
  }

  function save() {
    return runStrict(async () => {
      settings.value = await api.saveSettings({
        siteId: (settings.value.siteId ?? '').trim(),
        siteName: (settings.value.siteName ?? '').trim(),
        description: (settings.value.description ?? '').trim(),
        domain: (settings.value.domain ?? '').trim(),
      })
    })
  }

  function savePeer() {
    return runStrict(async () => {
      await api.savePeer({ ...peerForm })
      peerOpen.value = false
      Object.assign(peerForm, { siteId: '', name: '', description: '', domain: '', manifestUrl: '' })
      const body = await api.peers()
      peers.value = body.records ?? []
    })
  }

  function removePeer(siteId?: string) {
    if (!siteId) return Promise.resolve()
    return runStrict(async () => {
      await api.deletePeer(siteId)
      const body = await api.peers()
      peers.value = body.records ?? []
    })
  }

  /** 手动拉取：强制重拉全部未屏蔽友站（绕过 5 分钟缓存），返回可展示的结果摘要。 */
  function pullPeers(): Promise<McInteropPeerPull> {
    return runStrict(() => api.refreshPeers())
  }

  async function togglePeerEnabled(peer: McInteropPeer) {
    const siteId = peer.siteId ?? peer.id
    if (!siteId) return
    return runStrict(async () => {
      await api.setPeerEnabled(siteId, peer.enabled === false)
      const body = await api.peers()
      peers.value = body.records ?? []
    })
  }

  onMounted(load)

  return reactive({
    loading, error, settings, peers, peerOpen, peerForm,
    load, save, savePeer, removePeer, pullPeers, togglePeerEnabled,
  })
}

/** 手动拉取结果转 toast 文案：采合条数 + 逐站失败原因。 */
export function peerPullMessage(summary: McInteropPeerPull | undefined): string {
  if (!summary) return ''
  const results = summary.results ?? []
  const failures = results.filter(result => result.ok === false)
  const base = `手动拉取完成：采合友站条目 ${Number(summary.packs ?? 0)} 条（${Number(summary.elapsedMs ?? 0)}ms）`
  if (!failures.length) return base
  const reasons = failures
    .map(result => `${result.siteId ?? '?'}：${result.error ?? '失败'}`)
    .join('；')
  return `${base}；失败 ${failures.length} 站（${reasons}）`
}
