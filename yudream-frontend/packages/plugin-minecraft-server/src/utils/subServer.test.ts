import type { PlayerActivity, PlayerSubServerActivity } from '../types'
import { describe, expect, it } from 'vitest'
import { hasSubServerDimension, isDefaultSubServer, subServerBreakdown, subServerLabel } from './subServer'

function bucket(name: string, onlineMillis: number, afkMillis = 0): PlayerSubServerActivity {
  return { name, online: false, afk: false, onlineMillis, afkMillis }
}

function record(subServers: PlayerSubServerActivity[], totalOnlineMillis = 0): PlayerActivity {
  return {
    serverId: 's1',
    playerId: 'p1',
    playerName: 'admin',
    online: false,
    afk: false,
    totalOnlineMillis,
    totalAfkMillis: 0,
    updatedAt: 0,
    subServers,
  }
}

/** 与组件的 formatDuration 同口径，测试里只需要一个确定性的替身。 */
const duration = (value?: number) => `${Math.floor(Number(value || 0) / 1000)}s`

describe('subServerBreakdown', () => {
  it('有具名子服时不再展示兜底桶', () => {
    const details = subServerBreakdown(
      record([bucket('default', 35_000), bucket('fabric', 65_000), bucket('paper', 36_000)], 138_000),
      duration,
    )
    expect(details.map(detail => detail.name)).toEqual(['fabric', 'paper'])
    expect(details.some(detail => detail.label === '默认')).toBe(false)
  })

  it('只有兜底桶时照常展示，否则这一列会整个空掉', () => {
    const details = subServerBreakdown(record([bucket('default', 5_000)], 5_000), duration)
    expect(details.map(detail => detail.label)).toEqual(['默认'])
  })

  it('没有子服字段时返回空数组', () => {
    expect(subServerBreakdown(record([]), duration)).toEqual([])
    expect(subServerBreakdown(undefined, duration)).toEqual([])
    expect(subServerBreakdown(null, duration)).toEqual([])
  })

  it('按在线时长从多到少排列，而不是按后端落库顺序', () => {
    const details = subServerBreakdown(
      record([bucket('paper', 10_000), bucket('fabric', 90_000), bucket('lobby', 50_000)]),
      duration,
    )
    expect(details.map(detail => detail.name)).toEqual(['fabric', 'lobby', 'paper'])
  })

  it('时长相同则按展示名排序，保证渲染稳定', () => {
    const details = subServerBreakdown(record([bucket('zulu', 1_000), bucket('alpha', 1_000)]), duration)
    expect(details.map(detail => detail.name)).toEqual(['alpha', 'zulu'])
  })

  it('带上后端返回的全部字段，子服行才有状态与进出服时间可显示', () => {
    const details = subServerBreakdown(record([{
      name: 'fabric',
      online: true,
      afk: true,
      onlineMillis: 65_000,
      afkMillis: 4_000,
      currentOnlineSince: 111,
      currentAfkSince: 222,
      lastJoinedAt: 333,
      lastQuitAt: 444,
    }]), duration)
    expect(details[0]).toEqual({
      name: 'fabric',
      label: 'fabric',
      online: true,
      afk: true,
      onlineMillis: 65_000,
      afkMillis: 4_000,
      duration: '65s',
      afkDuration: '4s',
      currentOnlineSince: 111,
      currentAfkSince: 222,
      lastJoinedAt: 333,
      lastQuitAt: 444,
    })
  })

  it('缺失的时长按 0 处理，不产生 NaN', () => {
    const details = subServerBreakdown(record([
      { name: 'fabric', online: false, afk: false } as PlayerSubServerActivity,
    ]), duration)
    expect(details[0].onlineMillis).toBe(0)
    expect(details[0].duration).toBe('0s')
  })
})

describe('hasSubServerDimension', () => {
  it('混有具名子服时为真，即便还留着兜底桶', () => {
    expect(hasSubServerDimension(record([bucket('default', 1), bucket('fabric', 1)]))).toBe(true)
  })

  it('只有兜底桶时为假，不该展开出一行与玩家行相同的「默认」', () => {
    expect(hasSubServerDimension(record([bucket('default', 1)]))).toBe(false)
    expect(hasSubServerDimension(record([]))).toBe(false)
    expect(hasSubServerDimension(undefined)).toBe(false)
  })
})

describe('subServerLabel', () => {
  it('兜底桶与空名都显示为「默认」', () => {
    expect(subServerLabel('default')).toBe('默认')
    expect(subServerLabel('')).toBe('默认')
    expect(subServerLabel(undefined)).toBe('默认')
  })

  it('具名子服去掉首尾空白后原样显示', () => {
    expect(subServerLabel(' fabric ')).toBe('fabric')
  })
})

describe('isDefaultSubServer', () => {
  it('只认空名与 default，大小写敏感与后端一致', () => {
    expect(isDefaultSubServer('default')).toBe(true)
    expect(isDefaultSubServer('')).toBe(true)
    expect(isDefaultSubServer('fabric')).toBe(false)
    expect(isDefaultSubServer('Default')).toBe(false)
  })
})
