import { describe, expect, it } from 'vitest'
import { MAX_FLY_SPEED_SCALE, flySpeedScale } from './flySpeedPolicy'

describe('fly speed policy', () => {
  it('keeps close terrain navigation precise while scaling overview travel', () => {
    expect(flySpeedScale(-40)).toBe(1)
    expect(flySpeedScale(64)).toBe(1)
    expect(flySpeedScale(256)).toBe(2)
    expect(flySpeedScale(9_999)).toBe(MAX_FLY_SPEED_SCALE)
  })
})
