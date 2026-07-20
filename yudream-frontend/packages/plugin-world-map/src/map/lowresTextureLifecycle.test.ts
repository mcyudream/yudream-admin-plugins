import { describe, expect, it } from 'vitest'
import { shouldMarkLowresLoadFailed, shouldRetainLowresTexture } from './lowresTextureLifecycle'

describe('lowres texture lifecycle', () => {
  it('retains a texture only while its viewer and tile record are active', () => {
    expect(shouldRetainLowresTexture(false, false)).toBe(true)
    expect(shouldRetainLowresTexture(true, false)).toBe(false)
    expect(shouldRetainLowresTexture(false, true)).toBe(false)
  })

  it('does not turn expected request cancellation into a failed tile', () => {
    expect(shouldMarkLowresLoadFailed(false, true)).toBe(false)
    expect(shouldMarkLowresLoadFailed(true, false)).toBe(false)
    expect(shouldMarkLowresLoadFailed(false, false)).toBe(true)
  })
})
