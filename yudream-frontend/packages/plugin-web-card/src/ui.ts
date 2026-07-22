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
  const normalized = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}
