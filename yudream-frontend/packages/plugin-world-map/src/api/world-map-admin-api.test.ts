import { describe, expect, it } from 'vitest'
import { createWorldMapAdminApi } from './world-map-admin-api'

describe('world map admin API', () => {
  it('uses the plugin HTTP client for the task event stream URL', () => {
    const api = createWorldMapAdminApi({
      http: { url: (path: string) => '/api/plugins/world-map' + path },
    } as never)
    expect(api.taskEventsUrl()).toBe('/api/plugins/world-map/admin/tasks/events')
  })
})
