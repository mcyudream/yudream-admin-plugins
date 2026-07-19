import { describe, expect, it } from 'vitest'
import { layerVisibilityFromHash, mapIdFromHash, viewerHash } from './viewerHash'

const markerSets = [
  { id: 'towns', defaultVisible: true },
  { id: 'claims', defaultVisible: false },
]

describe('viewer share hash', () => {
  it('selects the map named in a share hash before loading a source', () => {
    expect(mapIdFromHash('#map=nether&view=flat')).toBe('nether')
    expect(mapIdFromHash('#map=%20')).toBeNull()
  })

  it('restores known marker layers and ignores unknown or malformed values', () => {
    const hash = '#layers=%7B%22towns%22%3Afalse%2C%22claims%22%3Atrue%2C%22unknown%22%3Afalse%7D'
    expect(layerVisibilityFromHash(hash, markerSets)).toEqual({ towns: false, claims: true })
    expect(layerVisibilityFromHash('#layers=not-json', markerSets)).toEqual({ towns: true, claims: false })
  })

  it('round-trips camera and layer state with URL-safe encoding', () => {
    const hash = viewerHash({
      mapId: 'overworld', viewMode: 'flat',
      position: { x: 1, y: 2, z: 3 }, target: { x: 4, y: 5, z: 6 }, zoom: 1.25,
      layerVisibility: { towns: false, claims: true },
    })
    expect(mapIdFromHash(hash)).toBe('overworld')
    expect(layerVisibilityFromHash(hash, markerSets)).toEqual({ towns: false, claims: true })
    expect(hash).toContain('layers=')
  })
})
