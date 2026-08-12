import { describe, expect, it } from 'vitest'
import { zipValidationError } from './mapFile'

describe('zipValidationError', () => {
  it('accepts ZIP files by extension and a recognised MIME type', () => {
    expect(zipValidationError({ name: 'world.ZIP', type: 'application/zip' })).toBeNull()
  })

  it('rejects files that are not ZIP archives', () => {
    expect(zipValidationError({ name: 'world.tar.gz', type: 'application/gzip' })).toBe('请选择 ZIP 格式的地图文件')
  })

  it('allows browsers that leave the MIME type empty', () => {
    expect(zipValidationError({ name: 'world.zip', type: '' })).toBeNull()
  })
})
