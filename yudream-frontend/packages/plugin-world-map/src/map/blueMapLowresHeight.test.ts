import { describe, expect, it } from 'vitest'
import { blueMapHeightFromMetadata, blueMapLowresDisplacedHeight } from './blueMapLowresHeight'

describe('BlueMap lowres height decoding', () => {
  it('decodes the renderer metadata as signed 16-bit terrain height', () => {
    expect(blueMapHeightFromMetadata(0, 0)).toBe(0)
    expect(blueMapHeightFromMetadata(0, 64)).toBe(64)
    expect(blueMapHeightFromMetadata(0x7f, 0xff)).toBe(32767)
    expect(blueMapHeightFromMetadata(0x80, 0x00)).toBe(-32767)
    expect(blueMapHeightFromMetadata(0xff, 0xff)).toBe(0)
  })

  it('keeps CPU hit correction aligned with the lowres vertex shader displacement', () => {
    expect(blueMapLowresDisplacedHeight(100, 50, 0, 64)).toBeCloseTo(64.98)
  })
})
