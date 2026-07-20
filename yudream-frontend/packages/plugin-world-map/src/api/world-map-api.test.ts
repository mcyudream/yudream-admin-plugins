import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpMapSource } from './world-map-api'

describe('HTTP world map source', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('aborts a pending generation tile when a map source is superseded', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise(() => {})
    }))
    const source = createHttpMapSource({
      http: {
        url: (path: string) => `/api/plugins/world-map${path}`,
        get: async () => ({ generationId: 'g-1' }),
      },
    } as never, 'survival')

    await source.loadSettings()
    void source.fetchHiresTile(0, 0)
    await Promise.resolve()
    source.dispose?.()

    expect(signal?.aborted).toBe(true)
  })

  it('uses generation-scoped URLs once settings have loaded', async () => {
    const fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ generationId: 'g-42' }),
    }))
    vi.stubGlobal('fetch', fetch)
    const source = createHttpMapSource({
      http: {
        url: (path: string) => `/api/plugins/world-map${path}`,
        get: async () => ({ generationId: 'g-42' }),
      },
    } as never, 'survival')

    await source.loadSettings()
    expect(source.lowresTileUrl(2, -1, 3)).toContain('/generations/g-42/tiles/lowres/2/-1/3')
  })

  it('unwraps host JSON envelopes and treats a missing optional hires tile as empty terrain', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ code: 200, data: { x: 0, z: 0, positions: [], indices: [] } }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ code: 1000, message: 'file missing', data: null }),
      })
    vi.stubGlobal('fetch', fetch)
    const source = createHttpMapSource({
      http: {
        url: (path: string) => `/api/plugins/world-map${path}`,
        get: async () => ({ generationId: 'g-42' }),
      },
    } as never, 'survival')

    await source.loadSettings()
    await expect(source.fetchHiresTile(0, 0)).resolves.toMatchObject({ x: 0, z: 0, positions: [], indices: [] })
    await expect(source.fetchHiresTile(1, 0)).resolves.toBeNull()
  })
})
