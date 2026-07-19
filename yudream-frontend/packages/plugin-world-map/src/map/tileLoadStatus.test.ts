import { describe, expect, it } from 'vitest'
import { tileLoadMessage } from './tileLoadStatus'

describe('tileLoadMessage', () => {
  it('keeps nearby terrain, overview work, and retries distinguishable', () => {
    expect(tileLoadMessage({ hiresQueued: 2, hiresLoading: 1, lowresQueued: 4, lowresLoading: 0, retrying: 1 }))
      .toBe('加载 地形 3 · 概览 4 · 重试 1')
  })

  it('does not show a loading state after all tile work settles', () => {
    expect(tileLoadMessage({ hiresQueued: 0, hiresLoading: 0, lowresQueued: 0, lowresLoading: 0, retrying: 0 }))
      .toBeNull()
  })
})
