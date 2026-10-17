import { describe, expect, it } from 'vitest'
import { createVoxelithMapApi, extractMarkerSets } from './voxelith-map-api'

describe('voxelith map public API', () => {
  const fakeSdk = {
    http: {
      url: (path: string) => '/api/plugins/voxelith-map' + path,
    },
  } as never

  it('builds the map base URL used by the viewer core', () => {
    const api = createVoxelithMapApi(fakeSdk)
    expect(api.mapBaseUrl('my-world')).toBe('/api/plugins/voxelith-map/maps/my-world')
  })

  it('builds the markers.json URL', () => {
    const api = createVoxelithMapApi(fakeSdk)
    expect(api.markersJsonUrl('my-world')).toBe('/api/plugins/voxelith-map/maps/my-world/markers.json')
  })

  it('passes the id straight through to the tile core without extra encoding', () => {
    const api = createVoxelithMapApi(fakeSdk)
    expect(api.mapBaseUrl('生存 世界')).toBe('/api/plugins/voxelith-map/maps/%E7%94%9F%E5%AD%98%20%E4%B8%96%E7%95%8C')
  })

  it('builds panorama artifact URLs and only busts the cache when asked', () => {
    const api = createVoxelithMapApi(fakeSdk)
    expect(api.panoramaUrl('my-world')).toBe('/api/plugins/voxelith-map/maps/my-world/panorama.png')
    expect(api.panoramaUrl('my-world', 3)).toBe('/api/plugins/voxelith-map/maps/my-world/panorama.png?v=3')
    expect(api.aerialUrl('my-world')).toBe('/api/plugins/voxelith-map/maps/my-world/aerial.png')
    expect(api.aerialUrl('my-world', 1)).toBe('/api/plugins/voxelith-map/maps/my-world/aerial.png?v=1')
    expect(api.panoramaMetaUrl('my-world')).toBe('/api/plugins/voxelith-map/maps/my-world/panorama.json')
  })

  it('extracts marker sets from both {sets} and raw array shapes', () => {
    expect(extractMarkerSets({ sets: [{ id: 'a' }] })).toEqual([{ id: 'a' }])
    expect(extractMarkerSets([{ id: 'b' }])).toEqual([{ id: 'b' }])
    expect(extractMarkerSets(null)).toEqual([])
  })
})
