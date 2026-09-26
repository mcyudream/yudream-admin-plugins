/** 通用展示、单位与错误信息提取工具。 */

/**
 * 提取后端可读错误信息。admin 错误体为 rawJson {code,message}，可能被 SDK/宿主
 * 再包一层或整体落进 error.message，逐层展开并尝试解析 JSON 后取 message。
 * 深层优先：axios 顶层 message（"Request failed with status code N"）只作最后
 * 兜底，真正的人话在更深层的 response.data.message。
 */
const AXIOS_STATUS_MESSAGE = /^Request failed with status code \d+$/

export function errorMessage(error: unknown, fallback: string): string {
  const seen = new Set<unknown>()
  const queue: unknown[] = [error]
  let ownMessage = ''
  let statusMessage = ''
  while (queue.length) {
    const current = queue.shift()
    if (current === null || current === undefined || typeof current !== 'object' || seen.has(current)) {
      continue
    }
    seen.add(current)
    const record = current as Record<string, unknown>
    for (const key of ['data', 'error', 'body', 'response', 'cause']) {
      if (record[key] !== undefined) {
        queue.push(record[key])
      }
    }
    const message = record.message
    if (typeof message === 'string' && message.trim()) {
      const parsed = tryParseJsonObject(message)
      if (parsed && typeof parsed.message === 'string' && parsed.message.trim()) {
        return parsed.message
      }
      if (AXIOS_STATUS_MESSAGE.test(message)) {
        if (!statusMessage) {
          statusMessage = message
        }
      }
      else if (!ownMessage) {
        ownMessage = message
      }
    }
  }
  return ownMessage || statusMessage || fallback
}

function tryParseJsonObject(text: string): Record<string, unknown> | null {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{')) {
    return null
  }
  try {
    const parsed = JSON.parse(trimmed) as unknown
    return typeof parsed === 'object' && parsed !== null ? parsed as Record<string, unknown> : null
  }
  catch {
    return null
  }
}

/** epoch 毫秒兼容 number / string；无效返回 null。 */
export function toEpochMs(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const num = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(num) && num > 0 ? num : null
}

export function formatDateTime(value: number | string | null | undefined): string {
  const ms = toEpochMs(value)
  if (ms === null) {
    return '-'
  }
  const date = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 相对时间：x 秒/分钟前；超过 1 小时回落绝对时间。 */
export function relativeSeenText(value: number | string | null | undefined): string {
  const ms = toEpochMs(value)
  if (ms === null) {
    return '从未上报'
  }
  const diff = Date.now() - ms
  if (diff <= 0) {
    return '刚刚'
  }
  if (diff < 60_000) {
    return `${Math.max(1, Math.floor(diff / 1000))} 秒前`
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  return formatDateTime(ms)
}

const CAPACITY_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']

/** 字节数转可读容量（1024 基）。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '-'
  }
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < CAPACITY_UNITS.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${CAPACITY_UNITS[unit]}`
}

/** MiB（1024 基）转可读容量。 */
export function formatMib(mib?: number): string {
  return formatBytes((mib ?? Number.NaN) * 1024 * 1024)
}

/** GiB（1024 基）转可读容量。 */
export function formatGib(gib?: number): string {
  return formatBytes((gib ?? Number.NaN) * 1024 * 1024 * 1024)
}

export function formatPercent(ratio?: number): string {
  if (!Number.isFinite(ratio)) {
    return '-'
  }
  return `${Math.round(Math.min(100, Math.max(0, ratio as number)))}%`
}

export function toNumberOr(value: unknown, fallback = 0): number {
  const num = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(num) ? num : fallback
}

/** 字节数人性化（文件/备份列表）。 */
export function formatSize(bytes: unknown): string {
  const value = Number(bytes ?? 0)
  if (!Number.isFinite(value) || value <= 0) {
    return '-'
  }
  if (value >= 1024 ** 3) {
    return `${(value / 1024 ** 3).toFixed(2)} GB`
  }
  if (value >= 1024 ** 2) {
    return `${(value / 1024 ** 2).toFixed(1)} MB`
  }
  return `${Math.round(value / 1024)} KB`
}
