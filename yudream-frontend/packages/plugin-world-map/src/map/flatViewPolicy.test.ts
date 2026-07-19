import { describe, expect, it } from 'vitest'
import { FLAT_VIEW_MAX_DISTANCE, FLAT_VIEW_MIN_DISTANCE } from './flatViewPolicy'

describe('flat view camera policy', () => {
  it('does not clamp the 2 km overview or a shared overview viewpoint', () => {
    expect(FLAT_VIEW_MIN_DISTANCE).toBeLessThan(1_200)
    expect(FLAT_VIEW_MAX_DISTANCE).toBeGreaterThanOrEqual(2_000)
  })
})
