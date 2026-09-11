export function errorMessage(error: unknown, fallback: string): string {
  const message = (error as { message?: string } | null | undefined)?.message
  return message && message.trim() ? message : fallback
}

/** 宿主会把 long 序列化为字符串，需要展示/比较时统一强转。 */
export function toNumber(value: unknown): number {
  const num = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(num) ? num : 0
}

export const SOURCE_TYPE_OPTIONS = [
  { label: '官网新闻（minecraft.net）', value: 'mcnet' },
  { label: '反馈隧道（Zendesk）', value: 'zendesk' },
]

export const PUSH_STATE_META: Record<string, { label: string, color: string }> = {
  pushed: { label: '已推送', color: 'green' },
  failed: { label: '推送失败', color: 'red' },
  skipped: { label: '未推送', color: 'gray' },
  '': { label: '待推送', color: 'arcoblue' },
}

export const LOG_MODE_META: Record<string, { label: string, color: string }> = {
  poll: { label: '定时轮询', color: 'arcoblue' },
  manual: { label: '手动轮询', color: 'orangered' },
  test: { label: '测试推送', color: 'gray' },
}

/** 下次轮询的人性化文案：remainSeconds 来自服务端快照，前端本地递减即可。 */
export function nextPollText(status: { enabled?: boolean, lastPollAt?: number, nextPollAtLabel?: string } | null | undefined, remainSeconds: number): string {
  if (!status)
    return ''
  if (!status.enabled)
    return '已暂停'
  if (!status.lastPollAt)
    return '即将执行（启用后首个检查点）'
  if (remainSeconds <= 0)
    return `即将执行${status.nextPollAtLabel ? `（计划 ${status.nextPollAtLabel}）` : ''}`
  const minutes = Math.max(1, Math.ceil(remainSeconds / 60))
  return `约 ${minutes} 分钟后${status.nextPollAtLabel ? `（${status.nextPollAtLabel}）` : ''}`
}
