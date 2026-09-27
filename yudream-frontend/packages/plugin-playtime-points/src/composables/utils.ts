import type { CheckInRewardRecord, SettlementRecord, SubSettlementRecord } from '../types'

/** 时间戳（毫秒）→ 本地可读时间；空值显示 -。 */
export function formatTime(value: number | string | null | undefined): string {  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const time = Number(value)
  if (!Number.isFinite(time) || time <= 0) {
    return '-'
  }
  return new Date(time).toLocaleString('zh-CN', { hour12: false })
}

/** 毫秒 → 「X 小时 Y 分钟」；不足 1 分钟显示 0 分钟。 */
export function formatDuration(millis: number): string {
  const minutes = Math.max(0, Math.floor((Number(millis) || 0) / 60_000))
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return hours > 0 ? `${hours} 小时 ${rest} 分钟` : `${rest} 分钟`
}

export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const response = (error as { response?: { data?: { message?: string } } }).response
    const message = response?.data?.message
    if (message) {
      return message
    }
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

/** 群组服拆分明细；整服口径（单机服、提供方旧版本或改造前的流水）没有该字段，按空数组处理。 */
export function subDetails(record: SettlementRecord): SubSettlementRecord[] {
  return record.subServers ?? []
}

/** 权重展示：有子服明细时不再有单一权重，显示「按子服」。 */
export function weightLabel(record: SettlementRecord): string {
  return subDetails(record).length ? '按子服' : `×${record.weight}`
}

/** 打卡积分的发放来源文案。 */
export function sourceLabel(source: string | null | undefined): string {
  if (source === 'REALTIME') {
    return '实时回调'
  }
  if (source === 'PULL') {
    return '定时拉取'
  }
  return source || '-'
}

/** 打卡积分计算方式文案。 */
export function checkInModeLabel(mode: string | null | undefined): string {
  return mode === 'HOURLY' ? '按时薪折算' : '固定金额'
}

/**
 * 有效在线时长的展示：不足 1 分钟但大于 0 时显示「不足 1 分钟」，
 * 0 显示「0 分钟」——与打卡记录页「有效在线 XX 分钟」同一口径（毫秒取证）。
 */
export function formatCheckInDuration(millis: number | null | undefined): string {
  const safe = Math.max(0, Math.floor(Number(millis) || 0))
  const minutes = Math.floor(safe / 60_000)
  if (minutes > 0) {
    return `${minutes} 分钟`
  }
  return safe > 0 ? '不足 1 分钟' : '0 分钟'
}

/**
 * 是否属于「非时长打卡按固定积分发放」（图片/文件/定位等没有时长的打卡，按时薪模式下的全局固定积分回退）。
 *
 * 判定只用后端已有的字段：按时薪模式 + 有效在线时长为 0 + 实际发放 > 0。
 * 按时薪折算的路径在 effectiveMillis = 0 时永远发不出积分，所以这个组合只可能是固定积分回退，
 * 与「有效在线 44 分钟 × 时薪 10 = 7.33 积分」天然区分开。
 */
export function isNonDurationFixedReward(record: CheckInRewardRecord): boolean {
  const duration = Math.max(0, Number(record.effectiveMillis) || 0)
  const credit = Number(record.credit)
  return record.mode === 'HOURLY' && duration <= 0 && Number.isFinite(credit) && credit > 0
}

/**
 * 一笔打卡积分的折算说明：
 * - 非时长打卡按固定积分发放：`非时长打卡 · 固定 3 积分`
 * - 按时薪折算并发放：`有效在线 44 分钟 × 时薪 10 = 7.33 积分`
 * - 按时薪折算但未发放：中文原因（没有有效在线时长且固定积分为 0 / 折算不足最小入账单位）
 * - 固定金额：`每次打卡 2 积分`
 */
export function checkInRewardLabel(record: CheckInRewardRecord): string {
  const credit = Number(record.credit)
  const paid = Number.isFinite(credit) && credit > 0
  if (record.mode === 'HOURLY') {
    if (isNonDurationFixedReward(record)) {
      // 没有时长的打卡（图片/文件/定位）按全局固定积分发放：不显示时薪折算过程，避免误读成折算结果。
      return `非时长打卡 · 固定 ${record.credit} 积分`
    }
    if (!paid) {
      return record.note || (Number(record.effectiveMillis) > 0
        ? '有效在线时长按时薪折算后不足 1 个最小入账单位，未发放积分'
        : '该打卡没有有效在线时长，未发放积分')
    }
    return `有效在线 ${formatCheckInDuration(record.effectiveMillis)} × 时薪 ${record.rate} = ${record.credit} 积分`
  }
  if (!paid) {
    return record.note || '本次未发放积分'
  }
  return `每次打卡 ${record.rate || record.credit} 积分`
}

/** 时薪换算示例：时薪 10 表示每小时 10 积分，44 分钟即 7.33 积分。 */
export function hourlyExample(hourlyPoints: string): string {
  const rate = Number(hourlyPoints)
  if (!Number.isFinite(rate) || rate <= 0) {
    return '填写每小时可获得的积分；例如时薪 10 表示每小时 10 积分，有效在线 44 分钟即 10 × 44 ÷ 60 = 7.33 积分。'
  }
  const sample = Math.round((rate * 44 / 60) * 100) / 100
  return `时薪 ${normalizeNumber(hourlyPoints)} 表示每小时 ${normalizeNumber(hourlyPoints)} 积分；例如有效在线 44 分钟即 ${normalizeNumber(hourlyPoints)} × 44 ÷ 60 = ${sample} 积分（按钱包货币精度四舍五入）。`
}

/** 去掉数值字符串多余的尾随 0 与小数点，仅用于展示。 */
function normalizeNumber(value: string): string {
  const text = String(value ?? '').trim()
  if (!text || !Number.isFinite(Number(text))) {
    return text || '0'
  }
  return String(Number(text))
}

/**
 * 「非时长打卡每次积分」的说明文案。
 *
 * 该值只在按时薪模式下作为「没有时长的打卡」（图片/文件/定位）的回退生效，且是全局值、不参与项目覆盖；
 * 固定金额模式下所有打卡都按上面的固定金额发放，它不生效。
 */
export function nonDurationFixedHint(fixedPoints: string, hourly: boolean): string {
  const base = '图片/文件/定位等没有时长的打卡按此金额发放，填 0 表示这类打卡不发积分。'
  if (!hourly) {
    return `${base}固定金额模式下所有打卡都按上面的固定金额发放，此值不生效。`
  }
  const value = normalizeNumber(fixedPoints)
  return value === '0'
    ? `${base}当前为 0：这类打卡不发放积分。`
    : `${base}当前每次发放 ${value} 积分（全局值，不随项目覆盖变化）。`
}
