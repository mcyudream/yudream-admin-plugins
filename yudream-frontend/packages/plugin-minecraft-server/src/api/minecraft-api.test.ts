import { describe, expect, it, vi } from 'vitest'
import { createMinecraftApi } from './minecraft-api'
import { zipValidationError } from '../utils/mapFile'

function createSdk() {
  return {
    http: {
      get: vi.fn(),
      post: vi.fn(),
      request: vi.fn(),
      blob: vi.fn(),
    },
  }
}

describe('minecraft API', () => {
  it('requests closed servers explicitly', () => {
    const sdk = createSdk()
    createMinecraftApi(sdk as never).list(2, 20, false, true)
    expect(sdk.http.get).toHaveBeenCalledWith('/servers?page=2&size=20&refresh=false&closed=true')
  })

  it('uses the supplied map endpoints and blob downloads', () => {
    const sdk = createSdk()
    const api = createMinecraftApi(sdk as never)
    api.saveMap('a/b', 'file-1')
    api.setMapPublicAccess('a/b', true)
    api.downloadAdminMap('a/b')
    api.deleteMap('a/b')
    api.downloadPublicMap('a/b')

    expect(sdk.http.post).toHaveBeenCalledWith('/admin/servers/a%2Fb/map', { fileId: 'file-1' })
    expect(sdk.http.request).toHaveBeenCalledWith('/admin/servers/a%2Fb/map/public', { method: 'PUT', data: { publicAccess: true } })
    expect(sdk.http.request).toHaveBeenCalledWith('/admin/servers/a%2Fb/map', { method: 'DELETE' })
    expect(sdk.http.blob).toHaveBeenNthCalledWith(1, '/admin/servers/a%2Fb/map/download')
    expect(sdk.http.blob).toHaveBeenNthCalledWith(2, '/servers/a%2Fb/map/download')
  })

  it('accepts ZIP filenames and rejects other files', () => {
    expect(zipValidationError({ name: 'world.ZIP', type: '' })).toBeNull()
    expect(zipValidationError({ name: 'world.zip', type: 'application/zip' })).toBeNull()
    expect(zipValidationError({ name: 'world.tar.gz', type: 'application/gzip' })).toBe('请选择 ZIP 格式的地图文件')
  })
})
