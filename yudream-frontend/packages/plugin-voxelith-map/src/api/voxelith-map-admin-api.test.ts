import { afterEach, describe, expect, it, vi } from 'vitest'
import { createVoxelithMapAdminApi, fetchImageObjectUrl } from './voxelith-map-admin-api'

describe('voxelith map admin API', () => {
  const fakeSdk = {
    http: {
      url: (path: string) => '/api/plugins/voxelith-map' + path,
    },
  } as never

  /** 记录每一次请求的假 sdk：用于钉住端点路径、HTTP 动词与请求体。 */
  function recordingSdk() {
    const calls: Array<{ kind: string, path: string, method?: string, body?: unknown }> = []
    const sdk = {
      http: {
        url: (path: string) => '/api/plugins/voxelith-map' + path,
        get: (path: string) => {
          calls.push({ kind: 'get', path })
          return Promise.resolve({})
        },
        post: (path: string, body?: unknown) => {
          calls.push({ kind: 'post', path, body })
          return Promise.resolve({})
        },
        request: (path: string, config: { method: string, data?: unknown }) => {
          calls.push({ kind: 'request', path, method: config.method, body: config.data })
          return Promise.resolve({})
        },
      },
    } as never
    return { sdk, calls }
  }

  it('builds the SSE task event stream URL', () => {
    const api = createVoxelithMapAdminApi(fakeSdk)
    expect(api.taskEventsUrl()).toBe('/api/plugins/voxelith-map/admin/tasks/events')
  })

  it('builds the preview image URL with step', () => {
    const api = createVoxelithMapAdminApi(fakeSdk)
    expect(api.previewImageUrl('m-1', 4)).toBe('/api/plugins/voxelith-map/admin/maps/m-1/preview.png?step=4')
  })

  it('builds the scoped window image URL', () => {
    const api = createVoxelithMapAdminApi(fakeSdk)
    expect(api.windowImageUrl('m-1', { minX: -512, minZ: -512, size: 256, step: 4 }))
      .toBe('/api/plugins/voxelith-map/admin/maps/m-1/window.png?minX=-512&minZ=-512&size=256&step=4')
  })

  it('reads the panorama status from the map-scoped admin endpoint', async () => {
    const { sdk, calls } = recordingSdk()
    await createVoxelithMapAdminApi(sdk).panorama('m-1')
    expect(calls).toEqual([{ kind: 'get', path: '/admin/maps/m-1/panorama' }])
  })

  it('posts the panorama bake parameters verbatim', async () => {
    const { sdk, calls } = recordingSdk()
    const payload = { width: 1920, height: 1080, yaw: 90, pitch: 30, fov: 70, distance: 1.5 }
    await createVoxelithMapAdminApi(sdk).triggerPanorama('m-1', payload)
    expect(calls).toEqual([{ kind: 'post', path: '/admin/maps/m-1/panorama', body: payload }])
  })

  it('starts the shard worker with POST and stops it with DELETE', async () => {
    const { sdk, calls } = recordingSdk()
    const api = createVoxelithMapAdminApi(sdk)
    const payload = { workerId: 'w-1', threads: 2, leaseMinutes: 60, idleMinutes: 5 }
    await api.startShardWorker('m-1', payload)
    await api.stopShardWorker('m-1')
    expect(calls).toEqual([
      { kind: 'post', path: '/admin/maps/m-1/shard-worker', body: payload },
      { kind: 'request', path: '/admin/maps/m-1/shard-worker', method: 'DELETE' },
    ])
  })

  it('encodes ids that contain spaces or slashes', async () => {
    const { sdk, calls } = recordingSdk()
    await createVoxelithMapAdminApi(sdk).shardWorker('生存 世界')
    expect(calls[0]?.path).toBe('/admin/maps/%E7%94%9F%E5%AD%98%20%E4%B8%96%E7%95%8C/shard-worker')
  })
})

describe('fetchImageObjectUrl', () => {
  const png = new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: 'image/png' })

  /** createObjectURL 的入参就是断言对象：必须是 Blob，不能是 { data, headers } 包装。 */
  function spyCreateObjectUrl(): Blob[] {
    const created: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation(((value: Blob) => {
      created.push(value)
      return 'blob:stub'
    }) as never)
    return created
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('unwraps the { data, headers } envelope returned by sdk.http.blob', async () => {
    const created = spyCreateObjectUrl()
    const sdk = {
      http: { blob: () => Promise.resolve({ data: png, headers: {} }) },
    } as never
    await expect(fetchImageObjectUrl(sdk, '/admin/maps/m-1/preview.png?step=4'))
      .resolves.toBe('blob:stub')
    expect(created).toEqual([png])
  })

  it('accepts a bare Blob as well', async () => {
    const created = spyCreateObjectUrl()
    const sdk = { http: { blob: () => Promise.resolve(png) } } as never
    await expect(fetchImageObjectUrl(sdk, '/admin/maps/m-1/preview.png')).resolves.toBe('blob:stub')
    expect(created).toEqual([png])
  })

  it('rejects when the endpoint returned no binary content', async () => {
    const sdk = {
      http: { blob: () => Promise.resolve({ data: undefined, headers: {} }) },
    } as never
    await expect(fetchImageObjectUrl(sdk, '/admin/maps/m-1/preview.png'))
      .rejects.toThrow('图片端点未返回二进制内容')
  })
})
