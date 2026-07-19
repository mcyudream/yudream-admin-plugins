import { describe, expect, it } from 'vitest'
import { markerAnchor } from './MarkerLayer'

describe('markerAnchor', () => {
  it('keeps a point marker at its declared world coordinate', () => {
    expect(markerAnchor({ position: { x: 12, y: 70, z: -8 } })?.toArray()).toEqual([12, 70, -8])
  })

  it('centers line and region navigation on the annotation vertices', () => {
    expect(markerAnchor({ points: [{ x: 0, y: 64, z: 0 }, { x: 12, y: 68, z: 6 }] })?.toArray())
      .toEqual([6, 66, 3])
  })

  it('does not navigate to malformed geometry', () => {
    expect(markerAnchor({ points: [{ x: Number.NaN, y: 64, z: 0 }] })).toBeNull()
  })
})
