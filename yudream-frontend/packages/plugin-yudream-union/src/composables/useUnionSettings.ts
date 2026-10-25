import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionSiteSettings } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { errorMessageOf, useUnionApiShell } from './useUnionApiShell'

export function useUnionSettings(sdk: YuDreamPluginSdk) {
  const { api, toast, loading, error, run } = useUnionApiShell(sdk)

  const settings = ref<UnionSiteSettings>({})
  const siteCode = ref('')
  const siteName = ref('')
  const namePrefix = ref('')
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
      namePrefix.value = body.namePrefix ?? ''
      keyFingerprint.value = body.keyFingerprint ?? ''
      features.value = body.features ?? []
      auditRetentionDays.value = Number(body.auditRetentionDays ?? 30)
      joinRetentionDays.value = Number(body.joinRetentionDays ?? 90)
    })
  }

  /** 保存不走 run：saving 独立驱动按钮 loading，错误写入 error 由页面渲染，成功给 toast。 */
  async function save() {
    if (!siteCode.value.trim()) {
      error.value = 'siteCode 为必填'
      return
    }
    saving.value = true
    error.value = ''
    try {
      settings.value = await api.saveSettings({
        siteCode: siteCode.value.trim(),
        siteName: siteName.value.trim(),
        namePrefix: namePrefix.value.trim(),
        auditRetentionDays: Math.max(0, Math.floor(auditRetentionDays.value)),
        joinRetentionDays: Math.max(0, Math.floor(joinRetentionDays.value)),
      })
      toast.success('已保存站点身份')
      await load()
    }
    catch (cause) {
      error.value = errorMessageOf(cause)
    }
    finally {
      saving.value = false
    }
  }

  onMounted(load)

  return reactive({
    loading, error, saving, settings, siteCode, siteName, namePrefix, keyFingerprint, features,
    auditRetentionDays, joinRetentionDays,
    load, save,
  })
}
