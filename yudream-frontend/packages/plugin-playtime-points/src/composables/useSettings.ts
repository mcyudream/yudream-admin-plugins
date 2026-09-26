import type { AdminOptions, SaveSettingsPayload, ServerOption } from '../types'
import type { PlaytimePointsApi } from '../api/playtime-points-api'
import { reactive, ref } from 'vue'
import { useFaToast } from '@yudream/components'
import { errorMessage } from './utils'

/** 管理端设置页状态：加载 options（含依赖可用性与服务器规则），组装保存。 */

/** 服务器权重编辑行：把后端字符串权重转成数字输入。 */
export interface EditableServerRule extends ServerOption {
  weightNumber: number
}

export function useSettings(api: PlaytimePointsApi) {
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const options = ref<AdminOptions | null>(null)
  const form = reactive({
    enabled: true,
    assetCode: 'POINT',
    minutesPerPoint: 60,
    subtractAfk: true,
  })
  const rules = ref<EditableServerRule[]>([])

  async function load() {
    loading.value = true
    try {
      const data = await api.admin.options()
      options.value = data
      form.enabled = data.settings.enabled
      form.assetCode = data.settings.assetCode
      form.minutesPerPoint = data.settings.minutesPerPoint
      form.subtractAfk = data.settings.subtractAfk
      rules.value = data.servers.map(server => ({
        ...server,
        weightNumber: server.weight === null || server.weight === '' ? 1 : Number(server.weight) || 1,
      }))
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function save() {
    if (!form.assetCode.trim()) {
      toast.error('请选择钱包货币类型')
      return
    }
    if (!Number.isFinite(form.minutesPerPoint) || form.minutesPerPoint < 1) {
      toast.error('每积分所需有效时长必须至少 1 分钟')
      return
    }
    for (const rule of rules.value) {
      if (!Number.isFinite(rule.weightNumber) || rule.weightNumber < 0 || rule.weightNumber > 999) {
        toast.error(`服务器「${rule.name}」的权重必须在 0-999 之间`)
        return
      }
    }
    saving.value = true
    try {
      const servers: Record<string, { weight: number, enabled: boolean }> = {}
      for (const rule of rules.value) {
        servers[rule.id] = { weight: Number(rule.weightNumber.toFixed(2)), enabled: rule.enabled }
      }
      const payload: SaveSettingsPayload = {
        enabled: form.enabled,
        assetCode: form.assetCode.trim().toUpperCase(),
        minutesPerPoint: Math.round(form.minutesPerPoint),
        subtractAfk: form.subtractAfk,
        servers,
      }
      await api.admin.saveSettings(payload)
      toast.success('积分设置已保存')
      await load()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  return { loading, saving, options, form, rules, load, save }
}
