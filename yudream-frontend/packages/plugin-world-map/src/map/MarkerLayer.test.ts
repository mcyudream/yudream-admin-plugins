import { describe, expect, it } from 'vitest'
import { markerAnchor, markerLabel, markerLabelAnchor } from './MarkerLayer'

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

describe('markerLabel', () => {
  it('normalizes whitespace and ignores missing labels', () => {
    expect(markerLabel({ label: '  Spawn\n  plaza  ' })).toBe('Spawn plaza')
    expect(markerLabel({ label: '   ' })).toBeNull()
    expect(markerLabel({})).toBeNull()
  })

  it('bounds label texture size for unusually long annotation text', () => {
    const label = markerLabel({ label: 'a'.repeat(80) })
    expect(label).toHaveLength(64)
    expect(label).toMatch(/\.\.\.$/)
  })
})

describe('markerLabelAnchor', () => {
  it('keeps labels above point and region geometry', () => {
    expect(markerLabelAnchor({ position: { x: 3, y: 70, z: 9 } })?.toArray()).toEqual([3, 75, 9])
    expect(markerLabelAnchor({ points: [{ x: 0, y: 64, z: 0 }, { x: 8, y: 64, z: 4 }] })?.toArray())
      .toEqual([4, 66.5, 2])
  })
})
