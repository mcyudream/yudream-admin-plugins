import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TimeValue, TimelineEventType } from '../types'

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

// 图片地址解析：站内文件走 SDK，外链原样保留
export function resolveImageUrl(sdk: YuDreamPluginSdk, url: string | null | undefined) {
  const raw = normalizeFileUrl(url)
  if (!raw) {
    return ''
  }
  return /^https?:\/\//i.test(raw) ? raw : sdk.files.assetUrl(raw)
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

/** yyyy-MM-dd → 「2024年5月1日」展示；dateLabel 存在时优先用 dateLabel。 */
export function formatEventDate(eventDate: string, dateLabel?: string) {
  const label = (dateLabel || '').trim()
  if (label) {
    return label
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec((eventDate || '').trim())
  if (!match) {
    return eventDate || ''
  }
  return `${Number(match[1])}年${Number(match[2])}月${Number(match[3])}日`
}

export function eventYear(eventDate: string) {
  const match = /^(\d{4})/.exec((eventDate || '').trim())
  return match ? match[1] : ''
}

export const EVENT_TYPE_OPTIONS = [
  { value: 'ARTICLE', label: '图文', description: '封面、图集与 Markdown 详情，适合活动回顾' },
  { value: 'TEXT', label: '文字', description: '纯文字卡片，适合公告、声明与短记' },
  { value: 'ELECTION', label: '换届', description: '届次与卸任/新任名册，适合组织换届' },
  { value: 'MILESTONE', label: '里程碑', description: '节点纪念，适合成立、周年与关键突破' },
  { value: 'AWARD', label: '荣誉', description: '奖项与表彰，适合获奖与荣誉时刻' },
] as const

export const EVENT_TYPE_META: Record<TimelineEventType, {
  label: string
  icon: string
  tagVariant: 'default' | 'secondary' | 'outline'
}> = {
  ARTICLE: { label: '图文', icon: 'i-ri:image-line', tagVariant: 'secondary' },
  TEXT: { label: '文字', icon: 'i-ri:quill-pen-line', tagVariant: 'outline' },
  ELECTION: { label: '换届', icon: 'i-ri:group-line', tagVariant: 'default' },
  MILESTONE: { label: '里程碑', icon: 'i-ri:flag-line', tagVariant: 'secondary' },
  AWARD: { label: '荣誉', icon: 'i-ri:medal-line', tagVariant: 'default' },
}

export function eventTypeMeta(type: string | null | undefined) {
  const key = (type || '').trim().toUpperCase() as TimelineEventType
  return EVENT_TYPE_META[key] || EVENT_TYPE_META.ARTICLE
}

export function eventTypeClass(type: string | null | undefined) {
  const key = (type || '').trim().toLowerCase()
  return EVENT_TYPE_META[key.toUpperCase() as TimelineEventType] ? key : 'article'
}

export function eventTypeChip(event: { eventType?: string, termLabel?: string }) {
  const meta = eventTypeMeta(event.eventType)
  const term = (event.termLabel || '').trim()
  if ((event.eventType || '').toUpperCase() === 'ELECTION' && term) {
    return `${meta.label} · ${term}`
  }
  return meta.label
}

export function splitRoster(text: string) {
  return text.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
}

export function joinRoster(members: string[] | null | undefined) {
  return (members || []).join('\n')
}
