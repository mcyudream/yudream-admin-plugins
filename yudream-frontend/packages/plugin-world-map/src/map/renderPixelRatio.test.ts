import { describe, expect, it } from 'vitest'
import { renderPixelRatio } from './renderPixelRatio'

describe('renderPixelRatio', () => {
  it('preserves the normal device ratio within the pixel budget', () => {
    expect(renderPixelRatio(2, 1920, 1080)).toBeCloseTo(Math.sqrt(8_000_000 / (1920 * 1080)))
    expect(renderPixelRatio(2, 1440, 900)).toBe(2)
  })

  it('caps invalid or excessively dense render targets', () => {
    expect(renderPixelRatio(4, 375, 812)).toBe(2)
    expect(renderPixelRatio(0, 1920, 1080)).toBe(1)
    expect(renderPixelRatio(2, 2560, 1215)).toBeLessThan(2)
  })
})
