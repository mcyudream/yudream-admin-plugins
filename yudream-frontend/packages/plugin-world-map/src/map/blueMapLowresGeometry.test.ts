import { describe, expect, it } from 'vitest'
import { createBlueMapLowresGeometry } from './blueMapLowresGeometry'

describe('createBlueMapLowresGeometry', () => {
  it('keeps BlueMap\'s one-pixel overlap at the local tile boundaries', () => {
    const geometry = createBlueMapLowresGeometry(500, 4)
    geometry.computeBoundingBox()
    expect(geometry.boundingBox?.min.x).toBeCloseTo(0.5)
    expect(geometry.boundingBox?.min.z).toBeCloseTo(0.5)
    expect(geometry.boundingBox?.max.x).toBeCloseTo(501.5)
    expect(geometry.boundingBox?.max.z).toBeCloseTo(501.5)
    geometry.dispose()
  })
})
