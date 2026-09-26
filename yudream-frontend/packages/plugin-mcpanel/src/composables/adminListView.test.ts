import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  clampPercent,
  isSnapshotFresh,
  metricFreshness,
  nodeFilterOptions,
  scheduleRunErrorText,
  scheduleRunFeedback,
  scheduleRunView,
  SNAPSHOT_FRESH_MS,
} from './adminListView.ts'

/**
 * 管理列表纯展示辅助回归：百分比钳制、快照新鲜度（过期/离线不冒充实时）、
 * 节点选项有界收集与截断说明、计划任务运行态呈现与“立即执行”反馈判定。
 */

describe('clampPercent', () => {
  it('钳制到 0-100 并取整', () => {
    assert.equal(clampPercent(-5), 0)
    assert.equal(clampPercent(133.7), 100)
    assert.equal(clampPercent(42.4), 42)
  })

  it('非法输入回退 fallback', () => {
    assert.equal(clampPercent(Number.NaN), 0)
    assert.equal(clampPercent('abc', 12), 12)
    assert.equal(clampPercent(undefined, 7), 7)
  })
})

describe('isSnapshotFresh', () => {
  const now = 1_000_000

  it('窗口内视为新鲜', () => {
    assert.equal(isSnapshotFresh(now - 5_000, now), true)
    assert.equal(isSnapshotFresh(now - SNAPSHOT_FRESH_MS, now), true)
  })

  it('过期快照不再冒充实时', () => {
    assert.equal(isSnapshotFresh(now - SNAPSHOT_FRESH_MS - 1, now), false)
    assert.equal(isSnapshotFresh(now - 3_600_000, now), false)
  })

  it('空/非法/未来时间不算新鲜', () => {
    assert.equal(isSnapshotFresh(null, now), false)
    assert.equal(isSnapshotFresh(undefined, now), false)
    assert.equal(isSnapshotFresh('', now), false)
    assert.equal(isSnapshotFresh('not-a-number', now), false)
    assert.equal(isSnapshotFresh(now + 61_000, now), false)
  })

  it('容忍 60 秒内时钟偏移', () => {
    assert.equal(isSnapshotFresh(now + 30_000, now), true)
  })
})

describe('metricFreshness', () => {
  const now = 1_000_000

  it('离线状态优先于快照时间', () => {
    assert.equal(
      metricFreshness({ status: 'offline', reportedAt: now - 1_000, nowMs: now }),
      'offline',
    )
  })

  it('在线且新鲜为 live，超龄为 stale', () => {
    assert.equal(metricFreshness({ status: 'online', reportedAt: now - 5_000, nowMs: now }), 'live')
    assert.equal(metricFreshness({ status: 'online', reportedAt: now - 120_000, nowMs: now }), 'stale')
  })

  it('未知状态按快照时间降级', () => {
    assert.equal(metricFreshness({ reportedAt: now - 1_000, nowMs: now }), 'live')
    assert.equal(metricFreshness({ nowMs: now }), 'stale')
  })
})

describe('nodeFilterOptions', () => {
  it('首项为全部节点并保持去重后的顺序', () => {
    const options = nodeFilterOptions([
      { id: 'b', name: '节点B' },
      { id: 'a', name: '节点A' },
      { id: 'b', name: '节点B重复' },
      { id: '', name: '无效' },
    ], 2)
    assert.deepEqual(options, [
      { label: '全部节点', value: 'all' },
      { label: '节点B', value: 'b' },
      { label: '节点A', value: 'a' },
    ])
  })

  it('缺名节点回退显示 id', () => {
    const options = nodeFilterOptions([{ id: 'id-1' }], 1)
    assert.equal(options[1]?.label, 'id-1')
  })

  it('总数超过已加载数时追加禁用说明项，不静默截断', () => {
    const nodes = Array.from({ length: 100 }, (_, index) => ({ id: `n${index}`, name: `节点${index}` }))
    const options = nodeFilterOptions(nodes, 137)
    assert.equal(options.length, 102)
    const notice = options.at(-1)
    assert.equal(notice?.disabled, true)
    assert.match(notice?.label ?? '', /100 \/ 137/)
    assert.equal(notice?.value, 'all')
  })

  it('加载完整时不追加说明项', () => {
    const options = nodeFilterOptions([{ id: 'a', name: 'A' }], 1)
    assert.equal(options.length, 2)
  })
})

describe('scheduleRunView', () => {
  it('failed 渲染失败并携带 lastError', () => {
    assert.deepEqual(
      scheduleRunView({ lastRunStatus: 'failed', lastError: '实例未运行' }),
      { variant: 'destructive', label: '失败', detail: '实例未运行' },
    )
    assert.deepEqual(
      scheduleRunView({ lastRunStatus: 'failed' }),
      { variant: 'destructive', label: '失败', detail: '上次执行失败' },
    )
  })

  it('success 渲染成功', () => {
    assert.deepEqual(
      scheduleRunView({ lastRunStatus: 'success', lastError: '' }),
      { variant: 'default', label: '成功', detail: '' },
    )
  })

  it('字段缺失时按 lastRunAt 退化为已运行/未运行', () => {
    assert.deepEqual(
      scheduleRunView({ lastRunAt: 1_700_000_000_000 }),
      { variant: 'secondary', label: '已运行', detail: '' },
    )
    assert.deepEqual(
      scheduleRunView({}),
      { variant: 'outline', label: '未运行', detail: '' },
    )
  })

  it('未知状态字符串不渲染为成功', () => {
    assert.equal(scheduleRunView({ lastRunStatus: 'weird' }).label, '未运行')
  })
})

describe('scheduleRunFeedback', () => {
  it('明确失败标志返回 failure', () => {
    assert.equal(scheduleRunFeedback({ ok: false }), 'failure')
    assert.equal(scheduleRunFeedback({ success: false }), 'failure')
    assert.equal(scheduleRunFeedback({ error: 'boom' }), 'failure')
    assert.equal(scheduleRunFeedback({ lastError: '实例未运行' }), 'failure')
    assert.equal(scheduleRunFeedback({ lastRunStatus: 'failed' }), 'failure')
  })

  it('空响应或无失败标志返回 success', () => {
    assert.equal(scheduleRunFeedback(null), 'success')
    assert.equal(scheduleRunFeedback(undefined), 'success')
    assert.equal(scheduleRunFeedback({}), 'success')
    assert.equal(scheduleRunFeedback({ ok: true }), 'success')
    assert.equal(scheduleRunFeedback('ok'), 'success')
  })
})

describe('scheduleRunErrorText', () => {
  it('优先取 error/lastError/message，缺失回退 fallback', () => {
    assert.equal(scheduleRunErrorText({ error: 'boom' }, 'x'), 'boom')
    assert.equal(scheduleRunErrorText({ lastError: '未运行' }, 'x'), '未运行')
    assert.equal(scheduleRunErrorText({ message: 'm' }, 'x'), 'm')
    assert.equal(scheduleRunErrorText({}, '执行失败'), '执行失败')
    assert.equal(scheduleRunErrorText(null, '执行失败'), '执行失败')
  })
})
