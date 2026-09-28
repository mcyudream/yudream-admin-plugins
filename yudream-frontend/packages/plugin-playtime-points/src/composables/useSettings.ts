import type { AdminOptions, CheckInRewardMode, ProjectOption, SaveSettingsPayload, ServerRule } from '../types'
import type { PlaytimePointsApi } from '../api/playtime-points-api'
import { computed, reactive, ref } from 'vue'
import { useFaToast } from '@yudream/components'
import { errorMessage } from './utils'

/** 管理端设置页状态：加载 options（依赖可用性、服务器与子服拓扑、项目列表、当前规则），组装保存。 */

/**
 * 设置表格行。服务器行带 `children` 子服行，交给 FaTable 的 tree 模式展开；
 * 子服权重未配置时沿用所属服务器的规则（服务器规则再缺省为权重 1、参与结算）。
 */
export interface SettingsRow {
  /** 服务器 ID 或 `serverId::subServer`，同时作为 FaTable 的 row-key。 */
  key: string
  kind: 'server' | 'sub'
  serverId: string
  name: string
  defaultServer: boolean
  online: number
  weightNumber: number
  enabled: boolean
  children?: SettingsRow[]
}

/** 打卡积分的按项目费率覆盖行；`points` 为空字符串表示继承全局。 */
export interface ProjectRewardRow {
  projectId: string
  name: string
  enabled: boolean
  /** 固定模式下是每次金额，按时薪模式下是每小时积分；空表示继承全局。 */
  points: string
}

/** 后端权重是十进制字符串；空值按默认权重 1，`0` 必须保留成 0（表示该子服不发放积分）。 */
function weightOf(value: string | null | undefined): number {
  if (value === null || value === undefined || value === '') {
    return 1
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 1
}

/** 金额是十进制字符串：接受整数或最多 4 位小数。 */
const POINTS_PATTERN = /^\d+(\.\d{1,4})?$/

/**
 * 每积分所需有效时长：后端可能回传数字，也可能回传数字字符串（同一接口里权重就是字符串），
 * 表单组件在输入过程中也可能给出字符串。统一按数字解析，空/非法回退后端默认值 60，
 * 避免拿字符串直接做 Number.isFinite 判断而误报「必须至少 1 分钟」。
 */
function minutesOf(value: number | string | null | undefined): number {
  if (value === null || value === undefined || value === '') {
    return 60
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 60
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
    checkInRewardEnabled: false,
    checkInRewardPoints: '1',
    checkInRewardRealtime: false,
    /** 计算方式：FIXED 每次固定积分（默认）/ HOURLY 按时薪折算。 */
    checkInRewardMode: 'FIXED' as CheckInRewardMode,
    /** 按时薪折算时每小时的积分数。 */
    checkInHourlyPoints: '1',
    /**
     * 「没有时长的打卡」（图片/文件/定位等非 MC 打卡）每次发放的积分；'0' 表示不发。
     * 只在按时薪模式下作为时长为 0 时的回退生效，是全局值、不参与项目覆盖。
     */
    checkInFixedPoints: '0',
  })
  const rules = ref<SettingsRow[]>([])
  const projectRules = ref<ProjectRewardRow[]>([])

  async function load() {
    loading.value = true
    try {
      const data = await api.admin.options()
      options.value = data
      form.enabled = data.settings.enabled
      form.assetCode = data.settings.assetCode
      form.minutesPerPoint = minutesOf(data.settings.minutesPerPoint)
      form.subtractAfk = data.settings.subtractAfk
      form.checkInRewardEnabled = data.settings.checkInRewardEnabled ?? false
      form.checkInRewardPoints = data.settings.checkInRewardPoints || '1'
      form.checkInRewardRealtime = data.settings.checkInRewardRealtime ?? false
      // 旧后端/旧设置文档不带这两个字段：读出来就是固定金额 + 时薪 1，与升级前行为一致。
      form.checkInRewardMode = data.settings.checkInRewardMode === 'HOURLY' ? 'HOURLY' : 'FIXED'
      form.checkInHourlyPoints = data.settings.checkInHourlyPoints || '1'
      // 非时长打卡积分同样是后加的字段：旧后端/旧设置文档读出来是 '0'（这类打卡不发），行为与 1.3.0 一致。
      form.checkInFixedPoints = data.settings.checkInFixedPoints ?? '0'
      const subRules = data.settings.subServers ?? {}
      rules.value = data.servers.map((server) => {
        const children: SettingsRow[] = (server.subServers ?? []).map(sub => ({
          key: `${server.id}::${sub.name}`,
          kind: 'sub' as const,
          serverId: server.id,
          name: sub.name,
          defaultServer: sub.defaultServer,
          online: sub.online,
          weightNumber: weightOf(subRules[`${server.id}::${sub.name}`]?.weight),
          enabled: subRules[`${server.id}::${sub.name}`]?.enabled ?? true,
        }))
        return {
          key: server.id,
          kind: 'server' as const,
          serverId: server.id,
          name: server.name,
          defaultServer: false,
          online: 0,
          weightNumber: weightOf(server.weight),
          enabled: server.enabled,
          children,
        }
      })
      const overrides = data.settings.checkInRewardProjectPoints ?? {}
      projectRules.value = (data.projects ?? []).map((project: ProjectOption) => ({
        projectId: project.id,
        name: project.name,
        enabled: project.enabled,
        points: overrides[project.id] ?? '',
      }))
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function validateWeight(row: SettingsRow): string | null {
    if (!Number.isFinite(row.weightNumber) || row.weightNumber < 0 || row.weightNumber > 999) {
      const label = row.kind === 'sub' ? `子服「${row.name}」` : `服务器「${row.name}」`
      return `${label}的权重必须在 0-999 之间`
    }
    return null
  }

  /** 校验打卡积分金额/时薪：必须大于 0、最多 4 位小数（货币精度由后端按货币类型再验一次）。 */
  function validatePoints(label: string, value: string): string | null {
    const text = value.trim()
    if (!text) {
      return `${label}不能为空`
    }
    if (!POINTS_PATTERN.test(text) || Number(text) <= 0) {
      return `${label}必须是大于 0 的十进制数字（最多 4 位小数）`
    }
    if (Number(text) > 1_000_000) {
      return `${label}不能超过 1000000`
    }
    return null
  }

  /** 当前选中货币的精度；未知（钱包不可用或未选中）时返回 null，此时不做精度相关的校验。 */
  function selectedAssetScale(): number | null {
    const code = form.assetCode.trim().toUpperCase()
    const asset = (options.value?.assets ?? []).find(item => item.code === code)
    return asset ? Math.max(0, Number(asset.scale) || 0) : null
  }

  /** 小数位数（去掉尾随 0，与后端 stripTrailingZeros 同一口径：'0.0000' 视为 0 位）。 */
  function decimalScale(text: string): number {
    const trimmed = text.trim().replace(/0+$/, '').replace(/\.$/, '')
    const dot = trimmed.indexOf('.')
    return dot < 0 ? 0 : trimmed.length - dot - 1
  }

  /**
   * 非时长打卡积分校验：**0 是合法值**（表示这类打卡不发积分）；大于 0 时不超过 1000000、最多 4 位小数，
   * 且不超过当前货币精度（它是最终入账金额，不经过折算）。与后端校验同一口径。
   */
  function validateFixedPoints(label: string, value: string): string | null {
    const text = value.trim()
    if (!text) {
      return `${label}不能为空（填 0 表示这类打卡不发积分）`
    }
    if (!POINTS_PATTERN.test(text)) {
      return `${label}必须是不小于 0 的十进制数字（最多 4 位小数）`
    }
    if (Number(text) > 1_000_000) {
      return `${label}不能超过 1000000`
    }
    const scale = decimalScale(text)
    const assetScale = selectedAssetScale()
    if (assetScale !== null && scale > assetScale) {
      return `${label}最多支持 ${assetScale} 位小数（当前货币类型）`
    }
    return null
  }

  /**
   * 按时薪折算的额外校验：按当前货币精度折算后不能恒为 0
   * （0 位精度货币下时薪低于 0.5，连整整一小时都折算不出 1 分，等于永远不发）。
   */
  function validateHourlyRate(label: string, value: string): string | null {
    const problem = validatePoints(label, value)
    if (problem) {
      return problem
    }
    const scale = selectedAssetScale()
    if (scale === null) {
      return null
    }
    const minimum = 0.5 * 10 ** -scale
    if (Number(value.trim()) + 1e-9 < minimum) {
      return `${label}按当前货币精度（${scale} 位小数）折算后为 0，请提高到至少 ${minimum}`
    }
    return null
  }

  /** 当前模式下的费率标签：固定模式是每次金额，按时薪模式是每小时积分。 */
  const rateLabel = computed(() => (form.checkInRewardMode === 'HOURLY' ? '打卡积分时薪' : '打卡积分金额'))

  async function save() {
    if (!form.assetCode.trim()) {
      toast.error('请选择钱包货币类型')
      return
    }
    const minutes = minutesOf(form.minutesPerPoint)
    if (!Number.isFinite(minutes) || minutes < 1) {
      toast.error('每积分所需有效时长必须至少 1 分钟')
      return
    }
    for (const row of rules.value) {
      const problem = validateWeight(row)
      if (problem) {
        toast.error(problem)
        return
      }
      for (const child of row.children ?? []) {
        const childProblem = validateWeight(child)
        if (childProblem) {
          toast.error(childProblem)
          return
        }
      }
    }
    const hourly = form.checkInRewardMode === 'HOURLY'
    if (form.checkInRewardEnabled) {
      // 空值同样报「不能为空」：与升级前对固定金额的处理一致，避免保存出非预期的默认值。
      const globalProblem = hourly
        ? validateHourlyRate('打卡积分时薪', form.checkInHourlyPoints)
        : validatePoints('每次打卡积分', form.checkInRewardPoints)
      if (globalProblem) {
        toast.error(globalProblem)
        return
      }
      // 非时长打卡积分两种模式下都会提交给后端，因此都校验；报错文案里带字段名，便于定位。
      const fixedProblem = validateFixedPoints('非时长打卡每次积分', form.checkInFixedPoints)
      if (fixedProblem) {
        toast.error(fixedProblem)
        return
      }
    }
    for (const row of projectRules.value) {
      if (!row.points.trim()) {
        continue
      }
      const label = `项目「${row.name || row.projectId}」的${rateLabel.value}`
      const problem = hourly ? validateHourlyRate(label, row.points) : validatePoints(label, row.points)
      if (problem) {
        toast.error(problem)
        return
      }
    }
    saving.value = true
    try {
      const servers: Record<string, ServerRule> = {}
      const subServers: Record<string, ServerRule> = {}
      for (const row of rules.value) {
        servers[row.serverId] = { weight: Number(row.weightNumber.toFixed(2)), enabled: row.enabled }
        for (const child of row.children ?? []) {
          subServers[child.key] = { weight: Number(child.weightNumber.toFixed(2)), enabled: child.enabled }
        }
      }
      const checkInRewardProjectPoints: Record<string, string> = {}
      for (const row of projectRules.value) {
        const points = row.points.trim()
        if (points) {
          checkInRewardProjectPoints[row.projectId] = points
        }
      }
      const payload: SaveSettingsPayload = {
        enabled: form.enabled,
        assetCode: form.assetCode.trim().toUpperCase(),
        minutesPerPoint: Math.round(form.minutesPerPoint),
        subtractAfk: form.subtractAfk,
        servers,
        subServers,
        checkInRewardEnabled: form.checkInRewardEnabled,
        checkInRewardPoints: form.checkInRewardPoints.trim() || '1',
        checkInRewardRealtime: form.checkInRewardEnabled && form.checkInRewardRealtime,
        checkInRewardProjectPoints,
        checkInRewardMode: form.checkInRewardMode,
        checkInHourlyPoints: form.checkInHourlyPoints.trim() || '1',
        // 非时长打卡积分允许 0（表示不发），因此这里只兜底空串，不能像上面那样回退成 1。
        checkInFixedPoints: form.checkInFixedPoints.trim() || '0',
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

  return { loading, saving, options, form, rules, projectRules, rateLabel, load, save }
}
