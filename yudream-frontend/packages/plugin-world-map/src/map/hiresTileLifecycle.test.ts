import { describe, expect, it } from 'vitest'
import { shouldRetainHiresTile } from './hiresTileLifecycle'

describe('hires tile lifecycle', () => {
  it('keeps decoded terrain resident after a camera move', () => {
    expect(shouldRetainHiresTile(false)).toBe(true)
  })

  it('drops terrain only after the viewer disposes it', () => {
    expect(shouldRetainHiresTile(true)).toBe(false)
  })
})
