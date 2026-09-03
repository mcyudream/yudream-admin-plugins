import type { TimeValue } from '../types'
import type { YuDreamPluginBlobResponse } from '@yudream/plugin-sdk'

export function fileToBase64(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const value = String(reader.result || '')
      const commaIndex = value.indexOf(',')
      resolve(commaIndex >= 0 ? value.slice(commaIndex + 1) : value)
    }
    reader.onerror = () => reject(reader.error || new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}

export function saveBlobResponse(response: YuDreamPluginBlobResponse, fallbackName: string) {
  if (typeof document === 'undefined') {
    return
  }
  const filename = resolveFilename(header(response.headers, 'content-disposition'), fallbackName)
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function header(headers: Record<string, string>, name: string) {
  return Object.entries(headers || {}).find(([key]) => key.toLowerCase() === name)?.[1]
}

function resolveFilename(disposition: string | undefined, fallbackName: string) {
  if (!disposition) {
    return fallbackName
  }
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1])
  }
  const match = disposition.match(/filename="?([^";]+)"?/i)
  return match?.[1] ? decodeURIComponent(match[1]) : fallbackName
}

export function errorMessage(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback
}

export function todayText() {
  const date = new Date()
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

export function normalizeTime(value: TimeValue) {
  if (value == null || value === '') {
    return 0
  }
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value
    return validTimestamp(new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000)).getTime())
  }
  if (typeof value === 'number') {
    return validTimestamp(value)
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    return validTimestamp(numeric)
  }
  return validTimestamp(Date.parse(value))
}

function validTimestamp(value: number) {
  if (!Number.isFinite(value) || value <= 0) {
    return 0
  }
  return value < 10000000000 ? value * 1000 : value
}

export function formatTime(value: TimeValue) {
  const timestamp = normalizeTime(value)
  return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '-'
}

// 管理员手动结束（CLOSED）或活动结束时间已过，均视为已结束
export function isActivityEnded(status: string, activityEnd: TimeValue) {
  if (status === 'CLOSED') {
    return true
  }
  const end = normalizeTime(activityEnd)
  return end > 0 && Date.now() > end
}

export function formatDate(value: TimeValue) {
  const timestamp = normalizeTime(value)
  if (!timestamp) {
    return '-'
  }
  const date = new Date(timestamp)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function formatTimeRange(start: TimeValue, end: TimeValue) {
  const startTimestamp = normalizeTime(start)
  const endTimestamp = normalizeTime(end)
  if (!startTimestamp && !endTimestamp) {
    return '时间待定'
  }
  if (startTimestamp && endTimestamp) {
    return `${formatTime(startTimestamp)} ~ ${formatTime(endTimestamp)}`
  }
  return startTimestamp ? `${formatTime(startTimestamp)} 起` : `截至 ${formatTime(endTimestamp)}`
}

export function formatFileSize(value: number) {
  if (!value) {
    return '-'
  }
  if (value < 1024 * 1024) {
    return `${Math.max(1, Math.round(value / 1024))} KB`
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

export function toDatetimeLocalText(value: TimeValue) {
  const timestamp = normalizeTime(value)
  if (!timestamp) {
    return ''
  }
  const date = new Date(timestamp)
  const pad = (input: number) => String(input).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function datetimeLocalToEpoch(text: string) {
  if (!text) {
    return 0
  }
  const timestamp = new Date(text).getTime()
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : 0
}
