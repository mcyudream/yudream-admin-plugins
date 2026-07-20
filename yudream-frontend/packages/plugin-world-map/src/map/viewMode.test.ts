import { describe, expect, it } from 'vitest'
import { FLAT_FOG, PERSPECTIVE_FOG, fogForViewMode } from './viewMode'

describe('map view mode rendering configuration', () => {
  it('uses a fog envelope that contains the flat camera height', () => {
    expect(fogForViewMode('flat')).toBe(FLAT_FOG)
    expect(FLAT_FOG.near).toBeGreaterThan(2_000)
  })

  it('keeps perspective fog compact for detailed terrain depth cues', () => {
    expect(fogForViewMode('perspective')).toBe(PERSPECTIVE_FOG)
    expect(PERSPECTIVE_FOG.far).toBeLessThan(FLAT_FOG.far)
  })
})
