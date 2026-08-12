/** Formats a millisecond timestamp; returns a placeholder for missing or invalid values. */
export function formatDateTime(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value <= 0) return '暂无'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '暂无'
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** Formats part/total as a percentage; returns '—' when the total is not positive or inputs are invalid. */
export function formatPercent(part: number | null | undefined, total: number | null | undefined): string {
  if (part == null || total == null || !Number.isFinite(part) || !Number.isFinite(total) || total <= 0) return '—'
  return `${Math.round(part / total * 100)}%`
}

/** Formats an activity timeline bucket (ISO date or `yyyy-MM-dd'T'HH:00XXX`) for axis labels. */
export function formatBucket(bucket: string): string {
  if (!bucket) return ''
  return bucket.includes('T') ? bucket.slice(5, 16).replace('T', ' ') : bucket.slice(5)
}

/** Full label for a timeline bucket, used in tooltips. */
export function formatBucketTitle(bucket: string): string {
  if (!bucket) return ''
  return bucket.includes('T') ? bucket.slice(0, 16).replace('T', ' ') : bucket
}
