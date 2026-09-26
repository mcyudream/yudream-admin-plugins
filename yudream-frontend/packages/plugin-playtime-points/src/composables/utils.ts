/** 时间戳（毫秒）→ 本地可读时间；空值显示 -。 */
export function formatTime(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
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
