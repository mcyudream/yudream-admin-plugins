import { describe, expect, it } from 'vitest'
import { FLAT_VIEW_DEFAULT_ZOOM, FLAT_VIEW_MAX_DISTANCE, FLAT_VIEW_MIN_DISTANCE } from './flatViewPolicy'

describe('flat view policy', () => {
  it('starts closer than the widest permitted overview while retaining broad navigation bounds', () => {
    expect(FLAT_VIEW_DEFAULT_ZOOM).toBeGreaterThan(1)
    expect(FLAT_VIEW_MIN_DISTANCE).toBeLessThan(FLAT_VIEW_MAX_DISTANCE)
  })
})
