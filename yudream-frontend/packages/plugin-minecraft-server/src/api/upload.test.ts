import { afterEach, describe, expect, it, vi } from 'vitest'
import { clampUploadProgress, parseUploadEnvelope, uploadFileWithProgress } from './upload'

describe('upload helpers', () => {
  it('clamps transfer progress to 99', () => {
    expect(clampUploadProgress(0, 100)).toBe(0)
    expect(clampUploadProgress(50, 100)).toBe(50)
    expect(clampUploadProgress(100, 100)).toBe(99)
    expect(clampUploadProgress(10, 0)).toBe(0)
  })

  it('parses a successful host envelope', () => {
    expect(parseUploadEnvelope(200, { code: 200, message: 'ok', data: { id: 'file-1' } })).toEqual({ id: 'file-1' })
  })

  it('uses the backend message when the envelope fails', () => {
    expect(() => parseUploadEnvelope(200, { code: 500, message: '文件过大', data: null })).toThrow('文件过大')
    expect(() => parseUploadEnvelope(413, null)).toThrow('上传失败（HTTP 413）')
  })
})

describe('uploadFileWithProgress', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('posts the ZIP to the host file API without a timeout and reports transfer progress', async () => {
    const send = vi.fn()
    const setRequestHeader = vi.fn()
    const xhr: Record<string, unknown> = {
      status: 0,
      response: null,
      upload: {},
      open: vi.fn(),
      send,
      setRequestHeader,
    }
    vi.stubGlobal('XMLHttpRequest', function MockXMLHttpRequest() {
      return xhr
    })
    vi.stubGlobal('localStorage', { getItem: () => 'Bearer test-token' })

    const onProgress = vi.fn()
    const promise = uploadFileWithProgress(
      { files: { assetUrl: (path?: string) => `https://host${path}` } } as never,
      new File(['world'], 'world.zip', { type: 'application/zip' }),
      onProgress,
    )

    expect(xhr.open).toHaveBeenCalledWith('POST', 'https://host/api/files/upload')
    expect(setRequestHeader).toHaveBeenCalledWith('Authorization', 'Bearer test-token')
    expect(xhr.responseType).toBe('json')
    expect(send).toHaveBeenCalledTimes(1)
    const form = send.mock.calls[0][0] as FormData
    expect(form.get('module')).toBe('minecraft-server')
    expect(form.get('publicAccess')).toBe('false')
    expect((form.get('file') as File).name).toBe('world.zip')

    const upload = xhr.upload as { onprogress?: (event: ProgressEvent<XMLHttpRequestEventTarget>) => void }
    upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 100 } as ProgressEvent<XMLHttpRequestEventTarget>)
    upload.onprogress?.({ lengthComputable: true, loaded: 100, total: 100 } as ProgressEvent<XMLHttpRequestEventTarget>)
    expect(onProgress).toHaveBeenCalledWith(50)
    expect(onProgress).toHaveBeenCalledWith(99)
    expect(onProgress).not.toHaveBeenCalledWith(100)

    xhr.status = 200
    xhr.response = { code: 200, message: 'ok', data: { id: 'file-9', originalName: 'world.zip' } }
    ;(xhr.onload as () => void)()

    await expect(promise).resolves.toEqual({ id: 'file-9', originalName: 'world.zip' })
    expect(onProgress).not.toHaveBeenCalledWith(100)
  })
})
