import { describe, expect, it } from 'vitest'
import { blueMapLodForDistance, shouldLoadBlueMapHires } from './blueMapLodPolicy'

describe('BlueMap LOD policy', () => {
  it('uses the BlueMap lodFactor for distant LOD selection', () => {
    expect(blueMapLodForDistance(999, 1, 3, 5)).toBe(1)
    expect(blueMapLodForDistance(5_000, 1, 3, 5)).toBe(2)
    expect(blueMapLodForDistance(25_000, 1, 3, 5)).toBe(3)
  })

  it('avoids hires requests beyond the BlueMap detail distance', () => {
    expect(shouldLoadBlueMapHires(999)).toBe(true)
    expect(shouldLoadBlueMapHires(1_000)).toBe(false)
  })
})
