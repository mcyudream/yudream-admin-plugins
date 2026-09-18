import type { PlayerActivity, PlayerSubServerDetail } from '../types'

/** 没有子服维度时后端统一用的桶名；界面不直接暴露这个内部键。 */
export const DEFAULT_SUB_SERVER = 'default'

/** 这个桶是不是「没有子服维度」的兜底桶。 */
export function isDefaultSubServer(name?: string): boolean {
  const value = String(name ?? '').trim()
  return value === '' || value === DEFAULT_SUB_SERVER
}

/** 子服的展示名：兜底桶在界面上一律叫「默认」。 */
export function subServerLabel(name?: string): string {
  return isDefaultSubServer(name) ? '默认' : String(name).trim()
}

/**
 * 记录是否带真实的子服维度。
 *
 * 只有兜底桶的记录（单机服，或按子服上报之前的旧数据）不该展开成子行：那样只会得到一行与玩家行
 * 完全相同的「默认」。这里看的是原始桶，不看去掉兜底桶之后的展示结果。
 */
export function hasSubServerDimension(record?: PlayerActivity | null): boolean {
  const buckets = record?.subServers ?? []
  return buckets.some(bucket => !isDefaultSubServer(bucket.name))
}

/**
 * 一个玩家的按子服时长明细，在线时长从多到少排列。
 *
 * 排序放在前端：子服桶在后端按首次进入的顺序落库，直接渲染会随加入次序跳动。
 *
 * 一旦存在具名子服，兜底桶就不再展示：它是「按子服上报之前」那段历史的残留（当时整服被算作一个
 * 桶），既不是一台真实子服，混在具名子服里还会让人误以为分了却没分全。只有兜底桶的记录（单机服，
 * 或从未按子服上报过）仍照常展示，否则这一列会整个空掉。
 *
 * 注意这会让这类历史记录的两个累计值大于所列子服之和，差额就是被隐藏的那段历史时长。
 *
 * @param formatDuration 时长格式化函数，由调用方提供以复用它的展示口径
 */
export function subServerBreakdown(
  record: PlayerActivity | null | undefined,
  formatDuration: (value?: number) => string,
): PlayerSubServerDetail[] {
  const buckets = record?.subServers ?? []
  const named = buckets.filter(bucket => !isDefaultSubServer(bucket.name))
  const shown = named.length > 0 ? named : buckets
  return shown
    .map(bucket => ({
      name: bucket.name,
      label: subServerLabel(bucket.name),
      online: bucket.online,
      afk: bucket.afk,
      onlineMillis: Number(bucket.onlineMillis || 0),
      afkMillis: Number(bucket.afkMillis || 0),
      duration: formatDuration(bucket.onlineMillis),
      afkDuration: formatDuration(bucket.afkMillis),
      currentOnlineSince: bucket.currentOnlineSince,
      currentAfkSince: bucket.currentAfkSince,
      lastJoinedAt: bucket.lastJoinedAt,
      lastQuitAt: bucket.lastQuitAt,
    }))
    .sort((left, right) => right.onlineMillis - left.onlineMillis || left.label.localeCompare(right.label))
}
