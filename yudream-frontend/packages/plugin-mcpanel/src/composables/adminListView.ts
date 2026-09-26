import { toEpochMs } from './utils.ts'

/**
 * 管理列表纯展示辅助（Nodes / Instances / Schedules 共用）：
 * 快照新鲜度、百分比钳制、节点筛选选项的有界收集、计划任务运行态呈现。
 * 不依赖 Vue 与 SDK，可被 node:test 直接覆盖。
 */

/** 将任意数值钳制到 0-100 整数；非法输入回退 fallback。 */
export function clampPercent(value: unknown, fallback = 0): number {
  const num = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(num)) {
    return fallback
  }
  return Math.min(100, Math.max(0, Math.round(num)))
}

/** 节点快照的默认新鲜窗口：agents 每 5s 上报，超过该窗口视为“最后快照”而非实时。 */
export const SNAPSHOT_FRESH_MS = 30_000

/** 快照时间是否仍可当“实时”呈现；空/非法/明显未来（>60s 时钟偏移）一律不算新鲜。 */
export function isSnapshotFresh(
  reportedAt: number | string | null | undefined,
  nowMs: number,
  maxAgeMs = SNAPSHOT_FRESH_MS,
): boolean {
  const ms = toEpochMs(reportedAt)
  if (ms === null) {
    return false
  }
  if (ms - nowMs > 60_000) {
    return false
  }
  return nowMs - ms <= maxAgeMs
}

export type MetricFreshness = 'live' | 'stale' | 'offline'

/**
 * 指标呈现方式：live 正常实时展示；stale 显示数值但标注“最后快照”；
 * offline（节点离线）不冒充实时。未知状态按快照时间降级处理。
 */
export function metricFreshness(params: {
  status?: string | null
  reportedAt?: number | string | null
  nowMs?: number
  maxAgeMs?: number
}): MetricFreshness {
  if (String(params.status ?? '') === 'offline') {
    return 'offline'
  }
  return isSnapshotFresh(params.reportedAt, params.nowMs ?? Date.now(), params.maxAgeMs) ? 'live' : 'stale'
}

export interface FilterOption {
  label: string
  value: string
  disabled?: boolean
}

/** 节点筛选选项分页收集的默认上界：100/页 × 10 页。 */
export const NODE_OPTIONS_PAGE_SIZE = 100
export const NODE_OPTIONS_MAX_PAGES = 10

/**
 * 由已加载节点构建节点筛选选项；总数超过已加载数时追加一条禁用的说明项，
 * 避免“固定取 100 条静默截断”。同名/重复 id 去重，保持首现顺序。
 */
export function nodeFilterOptions(
  nodes: Array<{ id?: string | null, name?: string | null }>,
  total: number,
): FilterOption[] {
  const options: FilterOption[] = [{ label: '全部节点', value: 'all' }]
  const seen = new Set<string>()
  nodes.forEach((node) => {
    const id = String(node?.id ?? '')
    if (!id || seen.has(id)) {
      return
    }
    seen.add(id)
    options.push({ label: node.name || id, value: id })
  })
  if (Number(total) > seen.size) {
    options.push({
      label: `已加载前 ${seen.size} / ${Number(total)} 个节点，其余请按关键字筛选实例`,
      value: 'all',
      disabled: true,
    })
  }
  return options
}

/** 计划任务最近一次运行的呈现：标签变体、文案与失败详情（tooltip 用）。 */
export interface ScheduleRunView {
  variant: 'default' | 'secondary' | 'destructive' | 'outline'
  label: string
  detail: string
}

/**
 * 后端将提供 lastRunStatus='success'|'failed' 与 lastError；字段缺失时按
 * lastRunAt 退化为“已运行/未运行”，不把未知状态渲染成成功。
 */
export function scheduleRunView(row: Record<string, unknown>): ScheduleRunView {
  const status = String(row.lastRunStatus ?? '').trim().toLowerCase()
  const lastError = typeof row.lastError === 'string' ? row.lastError.trim() : ''
  if (status === 'failed') {
    return { variant: 'destructive', label: '失败', detail: lastError || '上次执行失败' }
  }
  if (status === 'success') {
    return { variant: 'default', label: '成功', detail: lastError }
  }
  if (!status && toEpochMs(row.lastRunAt as number | string | null | undefined) !== null) {
    return { variant: 'secondary', label: '已运行', detail: lastError }
  }
  return { variant: 'outline', label: '未运行', detail: '' }
}

export type RunFeedback = 'success' | 'failure'

/**
 * “立即执行”响应的反馈判定：只有后端明确未失败时才允许 success 提示；
 * ok/success=false、error/lastError 非空、lastRunStatus=failed 都视为失败。
 */
export function scheduleRunFeedback(result: unknown): RunFeedback {
  if (result && typeof result === 'object') {
    const record = result as Record<string, unknown>
    if (record.ok === false || record.success === false) {
      return 'failure'
    }
    const failureText = [record.error, record.lastError].find(value => typeof value === 'string' && value.trim())
    if (failureText) {
      return 'failure'
    }
    if (String(record.lastRunStatus ?? '').trim().toLowerCase() === 'failed') {
      return 'failure'
    }
  }
  return 'success'
}

/** 从失败响应中提取可读信息（无则使用 fallback 文案）。 */
export function scheduleRunErrorText(result: unknown, fallback: string): string {
  if (result && typeof result === 'object') {
    const record = result as Record<string, unknown>
    const text = [record.error, record.lastError, record.message]
      .find(value => typeof value === 'string' && value.trim())
    if (text) {
      return String(text).trim()
    }
  }
  return fallback
}
