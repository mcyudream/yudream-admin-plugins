import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  FeedbackActivity,
  FeedbackAssignee,
  FeedbackComment,
  FeedbackSeverity,
  FeedbackStatus,
  FeedbackTimelineAction,
  FeedbackTimelineEntry,
  FeedbackType,
  FeedbackUserOption,
} from '../types'

/**
 * 提取后端错误信息，缺失时回退为插件文案。
 *
 * <p>宿主 SDK 把响应体挂在 `error.response.data` 上，只取 `error.message` 会丢掉后端返回的
 * 具体原因（例如「所选成员不存在，请重新选择」），排查时只剩一句笼统的兜底文案。
 */
export function errorMessage(cause: unknown, fallback: string): string {
  const data = cause as { response?: { data?: { message?: unknown } }, message?: unknown } | null
  const bodyMessage = data?.response?.data?.message
  if (typeof bodyMessage === 'string' && bodyMessage.trim()) {
    return bodyMessage
  }
  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message
  }
  return fallback
}

interface StatusMeta {
  label: string
  variant: 'default' | 'secondary' | 'outline'
  tone: string
}

export const STATUS_META: Record<FeedbackStatus, StatusMeta> = {
  pending: { label: '待处理', variant: 'default', tone: 'rgb(var(--warning-6))' },
  processing: { label: '处理中', variant: 'default', tone: 'var(--color-primary-6, #165dff)' },
  resolved: { label: '已解决', variant: 'default', tone: 'rgb(var(--success-6))' },
  rejected: { label: '不予处理', variant: 'secondary', tone: 'var(--color-text-3)' },
  archived: { label: '已归档', variant: 'outline', tone: 'var(--color-text-3)' },
}

export const TYPE_META: Record<FeedbackType, { label: string, icon: string }> = {
  bug: { label: '缺陷反馈', icon: 'i-ri:bug-line' },
  suggestion: { label: '功能建议', icon: 'i-ri:lightbulb-line' },
  question: { label: '使用咨询', icon: 'i-ri:question-line' },
  other: { label: '其他', icon: 'i-ri:chat-3-line' },
}

export const SEVERITY_META: Record<FeedbackSeverity, { label: string, tone: string, weight: number }> = {
  low: { label: '轻微', tone: 'var(--color-text-3)', weight: 0 },
  normal: { label: '一般', tone: 'var(--color-text-2, #4e5969)', weight: 1 },
  high: { label: '严重', tone: 'rgb(var(--warning-6))', weight: 2 },
  critical: { label: '致命', tone: 'rgb(var(--danger-6))', weight: 3 },
}

export const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '待处理', value: 'pending' },
  { label: '处理中', value: 'processing' },
  { label: '已解决', value: 'resolved' },
  { label: '不予处理', value: 'rejected' },
  { label: '已归档', value: 'archived' },
]

/** 表单用状态选项：不含「全部」占位项。 */
export const STATUS_FORM_OPTIONS = STATUS_OPTIONS.filter(item => item.value !== '')

export const TYPE_OPTIONS = [
  { label: '全部类型', value: '' },
  { label: '缺陷反馈', value: 'bug' },
  { label: '功能建议', value: 'suggestion' },
  { label: '使用咨询', value: 'question' },
  { label: '其他', value: 'other' },
]

export const TYPE_FORM_OPTIONS = TYPE_OPTIONS.filter(item => item.value !== '')

export const SEVERITY_OPTIONS = [
  { label: '全部严重程度', value: '' },
  { label: '轻微', value: 'low' },
  { label: '一般', value: 'normal' },
  { label: '严重', value: 'high' },
  { label: '致命', value: 'critical' },
]

export const SEVERITY_FORM_OPTIONS = SEVERITY_OPTIONS.filter(item => item.value !== '')

/** 处理人数量上限，与后端 Feedback.MAX_ASSIGNEE_COUNT 保持一致。 */
export const MAX_ASSIGNEE_COUNT = 8

export function statusMeta(status: FeedbackStatus): StatusMeta {
  return STATUS_META[status] ?? STATUS_META.pending
}

export function typeLabel(type: FeedbackType): string {
  return TYPE_META[type]?.label ?? '其他'
}

export function typeIcon(type: FeedbackType): string {
  return TYPE_META[type]?.icon ?? 'i-ri:chat-3-line'
}

export function severityMeta(severity: FeedbackSeverity) {
  return SEVERITY_META[severity] ?? SEVERITY_META.normal
}

export function userDisplayName(user: FeedbackUserOption): string {
  if (user.nickname && user.nickname.trim()) {
    return user.nickname
  }
  if (user.username && user.username.trim()) {
    return user.username
  }
  return user.id
}

/** 提交人 / 处理人展示：后端已给出昵称，缺失时回退为「未知」而不是空白。 */
export function personLabel(name: string, id: string | null): string {
  if (name && name.trim()) {
    return name
  }
  return id ? `用户 ${id}` : '未知'
}

/** 处理人展示名；后端未解析出昵称时按内部 ID 兜底。 */
export function assigneeLabel(assignee: FeedbackAssignee): string {
  return personLabel(assignee.name, assignee.id)
}

/** 处理人名单的紧凑文案，用于列表单元格与提示。 */
export function assigneeNames(assignees: FeedbackAssignee[] | undefined): string {
  const list = assignees ?? []
  return list.map(assigneeLabel).join('、')
}

/**
 * 处理记录合并：把同一次操作产生的系统留痕与评论收进同一个气泡。
 *
 * <p>一次管理动作（例如「流转状态」）会同时写入一条留痕和一条评论，二者共用同一个时间戳；
 * 若拆成两个气泡展示，读起来像是发生了两件事。这里按「同一人 + 同一毫秒」归组，
 * 组内先列系统动作、再列评论正文，一个气泡就是一次完整的处理动作。
 *
 * <p>同一人同一毫秒出现第二条评论时另起气泡，避免把两段话拼成一段。
 */
export function buildTimeline(comments: FeedbackComment[], activities: FeedbackActivity[]): FeedbackTimelineEntry[] {
  type Raw =
    | { kind: 'activity', actorId: string, actorName: string, createdAt: number, createdAtLabel: string, activity: FeedbackActivity, index: number }
    | { kind: 'comment', actorId: string, actorName: string, createdAt: number, createdAtLabel: string, comment: FeedbackComment }

  const raw: Raw[] = []
  for (const [index, activity] of (activities ?? []).entries()) {
    raw.push({
      kind: 'activity',
      actorId: activity.actorId,
      actorName: activity.actorName,
      createdAt: activity.createdAt,
      createdAtLabel: activity.createdAtLabel,
      activity,
      index,
    })
  }
  for (const comment of comments ?? []) {
    raw.push({
      kind: 'comment',
      actorId: comment.authorId,
      actorName: comment.authorName,
      createdAt: comment.createdAt,
      createdAtLabel: comment.createdAtLabel,
      comment,
    })
  }

  raw.sort((left, right) => {
    if (left.createdAt !== right.createdAt) {
      return left.createdAt - right.createdAt
    }
    return left.kind === 'activity' ? -1 : 1
  })

  const groups: FeedbackTimelineEntry[] = []
  for (const entry of raw) {
    const last = groups[groups.length - 1]
    const sameSource = Boolean(last)
      && last.actorId === entry.actorId
      && last.createdAt === entry.createdAt
    const canMerge = sameSource && !(entry.kind === 'comment' && last.content)
    if (!canMerge) {
      groups.push({
        key: `group-${entry.actorId}-${entry.createdAt}-${groups.length}`,
        actorId: entry.actorId,
        actorName: entry.actorName,
        createdAt: entry.createdAt,
        createdAtLabel: entry.createdAtLabel,
        actions: [],
        content: '',
        adminReply: false,
        internal: false,
      })
    }
    const group = groups[groups.length - 1]
    if (entry.kind === 'activity') {
      const action: FeedbackTimelineAction = {
        key: `action-${entry.activity.action}-${entry.createdAt}-${entry.index}`,
        action: entry.activity.action,
        actionLabel: entry.activity.actionLabel,
        detail: entry.activity.detail,
      }
      group.actions.push(action)
      if (entry.activity.action === 'note') {
        group.internal = true
      }
    }
    else {
      group.content = entry.comment.content
      group.adminReply = entry.comment.adminReply
      group.internal = group.internal || entry.comment.internal
    }
  }
  return groups
}

/** 站内文件地址归一化为相对路径，保证入库的是与宿主环境无关的稳定值。 */
export function normalizeFileUrl(value: string | null | undefined): string {
  const raw = (value || '').trim()
  if (!raw) {
    return ''
  }
  return raw
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}

/** 图片地址解析：站内文件交回 SDK 拼绝对地址，外链原样保留。 */
export function resolveImageUrl(sdk: YuDreamPluginSdk, url: string | null | undefined): string {
  const raw = normalizeFileUrl(url)
  if (!raw) {
    return ''
  }
  return /^https?:\/\//i.test(raw) ? raw : sdk.files.assetUrl(raw)
}

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

/** YdRangePicker 的 yyyy-MM-dd 区间转成毫秒区间：起始取当天 0 点，结束取当天 23:59:59.999。 */
export function parseDayRange(range: string[] | undefined | null): { from: number | null, to: number | null } {
  if (!range || range.length < 2) {
    return { from: null, to: null }
  }
  return { from: dayStart(range[0]), to: dayEnd(range[1]) }
}

function dayStart(value: string | undefined): number | null {
  const matched = DATE_PATTERN.exec((value || '').trim())
  if (!matched) {
    return null
  }
  const date = new Date(Number(matched[1]), Number(matched[2]) - 1, Number(matched[3]), 0, 0, 0, 0)
  return date.getTime()
}

function dayEnd(value: string | undefined): number | null {
  const start = dayStart(value)
  return start === null ? null : start + 86_400_000 - 1
}
