import { describe, expect, it } from 'vitest'
import { shouldRetainHiresTile } from './hiresTileLifecycle'

describe('hires tile lifecycle', () => {
  it('keeps in-flight terrain when a camera move leaves that tile in the desired disk', () => {
    expect(shouldRetainHiresTile(false, true)).toBe(true)
  })

  it('drops terrain only when the viewer or tile demand is no longer active', () => {
    expect(shouldRetainHiresTile(true, true)).toBe(false)
    expect(shouldRetainHiresTile(false, false)).toBe(false)
  })
})
