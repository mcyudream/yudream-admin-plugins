import type { TodoPriority, TodoTask, TodoUserOption } from '../types'

/**
 * 提取后端错误信息，缺失时回退为插件文案。
 *
 * 宿主 SDK 把响应体挂在 `error.response.data` 上，只取 `error.message` 会丢掉后端
 * 返回的具体原因（例如「待办标题不能为空」），排查时只剩一句笼统的兜底文案。
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

export const PRIORITY_OPTIONS: { label: string, value: TodoPriority }[] = [
  { label: '较低', value: 'low' },
  { label: '普通', value: 'normal' },
  { label: '较高', value: 'high' },
  { label: '紧急', value: 'urgent' },
]

export const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '待办', value: 'pending' },
  { label: '已逾期', value: 'overdue' },
  { label: '已完成', value: 'done' },
  { label: '已归档', value: 'archived' },
]

export const PRIORITY_FILTER_OPTIONS = [
  { label: '全部优先级', value: '' },
  ...PRIORITY_OPTIONS.map(item => ({ label: item.label, value: item.value })),
]

interface PriorityMeta {
  label: string
  variant: 'default' | 'secondary' | 'outline'
  tone: string
  weight: number
}

const PRIORITY_META: Record<TodoPriority, PriorityMeta> = {
  low: { label: '较低', variant: 'secondary', tone: 'var(--color-text-3)', weight: 0 },
  normal: { label: '普通', variant: 'outline', tone: 'var(--color-text-2, #4e5969)', weight: 1 },
  high: { label: '较高', variant: 'default', tone: 'rgb(var(--warning-6))', weight: 2 },
  urgent: { label: '紧急', variant: 'default', tone: 'rgb(var(--danger-6))', weight: 3 },
}

export function priorityMeta(priority: TodoPriority): PriorityMeta {
  return PRIORITY_META[priority] ?? PRIORITY_META.normal
}

export function userDisplayName(user: TodoUserOption): string {
  if (user.nickname && user.nickname.trim()) {
    return user.nickname
  }
  if (user.username && user.username.trim()) {
    return user.username
  }
  return user.id
}

/** 截止时间的人性化文案：逾期天数、今天/明天、剩余天数或无截止。 */
export function dueText(task: TodoTask): string {
  if (!task.dueAt) {
    return '无截止时间'
  }
  // 全部用客户端本地时区从 epoch 计算，避免依赖服务端 dueAtLabel（可能处于不同时区导致日期错位）。
  const due = formatDue(task.dueAt)
  if (task.status === 'done') {
    return due
  }
  const now = Date.now()
  const startOfDay = (value: number) => {
    const date = new Date(value)
    date.setHours(0, 0, 0, 0)
    return date.getTime()
  }
  const diffDays = Math.round((startOfDay(Number(task.dueAt)) - startOfDay(now)) / 86_400_000)
  const time = formatClock(task.dueAt)
  if (task.overdue) {
    return diffDays < -1 ? `逾期 ${Math.abs(diffDays)} 天` : `逾期 · ${due}`
  }
  if (diffDays === 0) {
    return `今天 ${time}`
  }
  if (diffDays === 1) {
    return `明天 ${time}`
  }
  if (diffDays < 0) {
    return `已截止 · ${due}`
  }
  return `${diffDays} 天后 · ${due}`
}

/** 把 epoch 毫秒（或字符串形式的 epoch）格式化为「年-月-日 时:分」（客户端本地时区），用于截止时间精确展示。 */
export function formatDue(millis: number | string): string {
  const date = new Date(Number(millis))
  const pad = (value: number) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatClock(millis: number | string): string {
  const date = new Date(Number(millis))
  const hours = `${date.getHours()}`.padStart(2, '0')
  const minutes = `${date.getMinutes()}`.padStart(2, '0')
  return `${hours}:${minutes}`
}

const DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/

/** 把 YdDatePicker 的格式化字符串转成 epoch 毫秒，非法输入返回 null。 */
export function parseDateTimeInput(value: string): number | null {
  if (!value) {
    return null
  }
  const matched = DATE_TIME_PATTERN.exec(value.trim())
  if (!matched) {
    return null
  }
  const [, year, month, day, hour, minute] = matched
  const date = new Date(
    Number(year),
    Number(month) - 1,
    Number(day),
    hour ? Number(hour) : 23,
    minute ? Number(minute) : 59,
    0,
    0,
  )
  return date.getTime()
}

/** 把 epoch 毫秒转回 YdDatePicker 的字符串模型。 */
export function toDateTimeInput(millis: number | string | null): string {
  if (millis === null || millis === undefined || millis === '') {
    return ''
  }
  const date = new Date(Number(millis))
  const pad = (value: number) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
