import { describe, expect, it } from 'vitest'
import { findMarkers } from './markerSearch'

const markerSets = [{
  id: 'towns', label: 'Towns', markers: [
    { id: 'spawn', label: 'Spawn plaza', position: { x: 0, y: 70, z: 0 } },
    { id: 'north', label: 'North gate', position: { x: 40, y: 72, z: -18 } },
  ],
}, {
  id: 'claims', label: 'Claims', markers: [{ id: 'redwood', label: 'Redwood reserve', position: { x: 120, y: 80, z: 22 } }],
}]

describe('marker search', () => {
  it('searches marker and layer names case-insensitively', () => {
    expect(findMarkers(markerSets, 'spawn').map(result => result.label)).toEqual(['Spawn plaza'])
    expect(findMarkers(markerSets, 'CLAIM').map(result => result.label)).toEqual(['Redwood reserve'])
  })

  it('has no unbounded result list for large extension layers', () => {
    expect(findMarkers(markerSets, 'town', 1)).toHaveLength(1)
    expect(findMarkers(markerSets, '   ')).toEqual([])
  })
})
