import { describe, expect, it } from 'vitest'
import { tileRequestPriority } from './tileRequestPriority'

describe('tileRequestPriority', () => {
  it('keeps the center first and favours tiles in front of the camera at equal distance', () => {
    expect(tileRequestPriority(0, 0, 1, 0)).toBe(0)
    expect(tileRequestPriority(1, 0, 1, 0)).toBeLessThan(tileRequestPriority(0, 1, 1, 0))
    expect(tileRequestPriority(0, 1, 1, 0)).toBeLessThan(tileRequestPriority(-1, 0, 1, 0))
  })

  it('keeps flat overview loading radial when there is no horizontal view direction', () => {
    expect(tileRequestPriority(1, 0, 0, 0)).toBe(1)
    expect(tileRequestPriority(0, -1, 0, 0)).toBe(1)
  })
})
