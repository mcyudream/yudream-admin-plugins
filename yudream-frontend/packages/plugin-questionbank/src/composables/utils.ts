import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TimeValue } from '../types'

export function errorMessage(error: unknown, fallback = '操作失败') {
  if (error && typeof error === 'object') {
    const data = error as { response?: { data?: { message?: string } }, message?: string }
    return data.response?.data?.message || data.message || fallback
  }
  return fallback
}

export function normalizeTime(value: TimeValue) {
  if (value == null || value === '') {
    return 0
  }
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value
    return new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000)).getTime()
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    return numeric < 10000000000 ? numeric * 1000 : numeric
  }
  return Number.isFinite(Date.parse(String(value))) ? Date.parse(String(value)) : 0
}

export function formatTime(value: TimeValue) {
  const timestamp = normalizeTime(value)
  return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '-'
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

// 渲染前把内容里的文件引用解析成当前环境可访问的地址
export function resolveMarkdownFileUrls(sdk: YuDreamPluginSdk, text: string | null | undefined) {
  const raw = (text || '')
    .replace(/https?:\/\/[^/\s)"]+(?=\/api\/files\/)/gi, '')
    .replace(/\/proxy(?=\/api\/files\/)/gi, '')
  if (!raw) {
    return ''
  }
  return raw.replace(
    /(\]\(|src=")(\/api\/files\/[^)\s"]+)(\)|")/gi,
    (_match, prefix: string, url: string, suffix: string) => `${prefix}${sdk.files.assetUrl(url)}${suffix}`,
  )
}

export function optionKey(index: number) {
  return String.fromCharCode(65 + index)
}

export function difficultyLabel(value: number) {
  return '★'.repeat(Math.max(1, Math.min(5, value || 3)))
}

export const QUESTION_TYPE_OPTIONS = [
  { value: 'SINGLE', label: '单选题' },
  { value: 'MULTIPLE', label: '多选题' },
  { value: 'TRUE_FALSE', label: '判断题' },
  { value: 'FILL', label: '填空题' },
  { value: 'SHORT', label: '简答题' },
]

export function questionTypeLabel(type: string) {
  return QUESTION_TYPE_OPTIONS.find(item => item.value === type)?.label || type
}
