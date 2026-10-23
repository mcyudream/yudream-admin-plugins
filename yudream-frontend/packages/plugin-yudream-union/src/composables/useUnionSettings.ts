import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionSiteSettings } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { useUnionApiShell } from './useUnionApiShell'

export function useUnionSettings(sdk: YuDreamPluginSdk) {
  const { api, loading, error, run } = useUnionApiShell(sdk)

  const settings = ref<UnionSiteSettings>({})
  const siteCode = ref('')
  const siteName = ref('')
  const keyFingerprint = ref('')
  const features = ref<string[]>([])
  const auditRetentionDays = ref(30)
  const joinRetentionDays = ref(90)
  const saving = ref(false)

  async function load() {
    await run(async () => {
      const body = await api.settings()
      settings.value = body
      siteCode.value = body.siteCode ?? ''
      siteName.value = body.siteName ?? ''
      keyFingerprint.value = body.keyFingerprint ?? ''
      features.value = body.features ?? []
      auditRetentionDays.value = Number(body.auditRetentionDays ?? 30)
      joinRetentionDays.value = Number(body.joinRetentionDays ?? 90)
    })
  }

  function save() {
    if (!siteCode.value.trim()) {
      error.value = 'siteCode 为必填'
      return
    }
    return run(async () => {
      settings.value = await api.saveSettings({
        siteCode: siteCode.value.trim(),
        siteName: siteName.value.trim(),
        auditRetentionDays: Math.max(0, Math.floor(auditRetentionDays.value)),
        joinRetentionDays: Math.max(0, Math.floor(joinRetentionDays.value)),
      })
      await load()
    })
  }

  onMounted(load)

  return reactive({
    loading, error, saving, settings, siteCode, siteName, keyFingerprint, features,
    auditRetentionDays, joinRetentionDays,
    load, save,
  })
}
