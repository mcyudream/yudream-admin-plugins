export function errorText(cause: unknown, fallback: string) {
  if (cause && typeof cause === 'object') {
    const response = (cause as { response?: { data?: unknown } }).response?.data
    if (typeof response === 'string') return response
    if (response && typeof response === 'object') {
      const value = response as { message?: unknown; data?: { message?: unknown } }
      if (typeof value.message === 'string') return value.message
      if (typeof value.data?.message === 'string') return value.data.message
    }
    if (cause instanceof Error && cause.message) return cause.message
  }
  return fallback
}
export const uid = () => crypto.randomUUID()
export function dateTime(value?: number | string) {
  if (value === undefined || value === null || value === '') return '-'
  let timestamp: number
  if (typeof value === 'number') {
    timestamp = value
  }
  else {
    const text = value.trim()
    const numeric = Number(text)
    timestamp = /^\d+$/.test(text) && Number.isFinite(numeric) ? numeric : Date.parse(text)
  }
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '-'
  const ms = timestamp < 10000000000 ? timestamp * 1000 : timestamp
  const date = new Date(ms)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}
