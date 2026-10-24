import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McInteropPeer, McInteropPeerForm, McInteropSettings } from '../types'
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

  onMounted(load)

  return reactive({
    loading, error, settings, peers, peerOpen, peerForm,
    load, save, savePeer, removePeer,
  })
}
