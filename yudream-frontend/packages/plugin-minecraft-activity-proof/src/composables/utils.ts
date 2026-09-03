import type { TimeValue } from '../types'
import type { YuDreamPluginBlobResponse, YuDreamPluginSdk } from '@yudream/plugin-sdk'

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

export function toDateText(value: TimeValue) {
  const timestamp = normalizeTime(value)
  if (!timestamp) {
    return ''
  }
  const date = new Date(timestamp)
  const pad = (input: number) => String(input).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

// 范围选择器精确到日：开始取当日 00:00，结束取当日 23:59:59.999，保证所选结束日当天全天有效
export function dateRangeToEpochs(range: string[] | undefined) {
  if (!range?.length) {
    return { start: 0, end: 0 }
  }
  return { start: dayStart(range[0]), end: dayEnd(range[1]) }
}

function dayStart(text: string | undefined) {
  if (!text) {
    return 0
  }
  const timestamp = new Date(`${text}T00:00:00`).getTime()
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : 0
}

function dayEnd(text: string | undefined) {
  if (!text) {
    return 0
  }
  const timestamp = new Date(`${text}T23:59:59.999`).getTime()
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : 0
}

// 上传结果在开发环境会带 /proxy 前缀或绝对 origin，落库前统一还原成 /api/files/... 相对路径
export function normalizeFileUrl(value: string | null | undefined) {
  const raw = (value || '').trim()
  if (!raw) {
    return ''
  }
  return raw
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}

export function resolveFileUrl(sdk: YuDreamPluginSdk, value: string | null | undefined) {
  const normalized = normalizeFileUrl(value)
  return normalized ? sdk.files.assetUrl(normalized) : ''
}

export function normalizeMarkdownFileUrls(text: string | null | undefined) {
  const raw = text || ''
  if (!raw) {
    return ''
  }
  return raw
    .replace(/https?:\/\/[^/\s)"]+(?=\/api\/files\/)/gi, '')
    .replace(/\/proxy(?=\/api\/files\/)/gi, '')
}

// 渲染前把内容里的文件引用解析成当前环境可访问的地址
export function resolveMarkdownFileUrls(sdk: YuDreamPluginSdk, text: string | null | undefined) {
  const raw = text || ''
  if (!raw) {
    return ''
  }
  return normalizeMarkdownFileUrls(raw).replace(
    /(\]\(|src=")(\/api\/files\/[^)\s"]+)(\)|")/gi,
    (_match, prefix: string, url: string, suffix: string) => `${prefix}${sdk.files.assetUrl(url)}${suffix}`,
  )
}

export type ActivityStage = 'ENDED' | 'SIGNUP_PENDING' | 'SIGNUP_OPEN' | 'SIGNUP_CLOSED'

// 报名/活动到点即视为自动截止，前端按此口径展示状态
export function activityStage(status: string, signupStart: TimeValue, signupEnd: TimeValue, activityEnd: TimeValue): ActivityStage {
  if (isActivityEnded(status, activityEnd)) {
    return 'ENDED'
  }
  const now = Date.now()
  const start = normalizeTime(signupStart)
  if (start > 0 && now < start) {
    return 'SIGNUP_PENDING'
  }
  const end = normalizeTime(signupEnd)
  if (end > 0 && now > end) {
    return 'SIGNUP_CLOSED'
  }
  return 'SIGNUP_OPEN'
}

export function activityStageTag(stage: ActivityStage) {
  switch (stage) {
    case 'ENDED':
      return { variant: 'secondary' as const, text: '已结束' }
    case 'SIGNUP_PENDING':
      return { variant: 'outline' as const, text: '报名未开始' }
    case 'SIGNUP_CLOSED':
      return { variant: 'secondary' as const, text: '报名截止' }
    default:
      return { variant: 'outline' as const, text: '报名中' }
  }
}
