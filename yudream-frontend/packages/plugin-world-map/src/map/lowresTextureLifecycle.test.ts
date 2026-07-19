import { describe, expect, it } from 'vitest'
import { shouldRetainLowresTexture } from './lowresTextureLifecycle'

describe('lowres texture lifecycle', () => {
  it('retains a texture only while its viewer and tile record are active', () => {
    expect(shouldRetainLowresTexture(false, false)).toBe(true)
    expect(shouldRetainLowresTexture(true, false)).toBe(false)
    expect(shouldRetainLowresTexture(false, true)).toBe(false)
  })
})
