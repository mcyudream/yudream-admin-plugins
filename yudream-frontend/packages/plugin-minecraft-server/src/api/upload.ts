import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'

interface BackendEnvelope<T> {
  code: number
  message: string
  data: T
}

/**
 * 未完结 XHR 的兜底持有集合。Firefox 对每域名并发连接有上限，连接被占满时上传请求会排队；
 * 排队中的 XHR 若只被自身事件回调引用会被 GC 回收，请求以 NS_BINDING_ABORTED 静默中止且
 * 事件不再触发，外层 Promise 永不落定。完结前显式持有即可避免。
 */
const inflightUploads = new Set<XMLHttpRequest>()

export function clampUploadProgress(loaded: number, total: number): number {
  if (!(total > 0) || !Number.isFinite(loaded) || !Number.isFinite(total)) {
    return 0
  }
  return Math.min(99, Math.max(0, Math.round((loaded / total) * 100)))
}

export function parseUploadEnvelope(status: number, body: unknown): YuDreamPluginFileObject {
  const envelope = body as BackendEnvelope<YuDreamPluginFileObject> | null
  if (status >= 200 && status < 300 && envelope?.code === 200 && envelope.data) {
    return envelope.data
  }
  throw new Error(envelope?.message || `上传失败（HTTP ${status}）`)
}

/**
 * sdk.files.uploadImage 走宿主 axios 实例：60s 超时且拿不到上传进度。
 * 地图 ZIP 需要进度反馈且不能限时，这里改用 XHR 手动携带宿主 token 打同一个 /api/files/upload。
 * 传输期间最多报 99；100 由调用方在后续绑定完成后上报。
 */
export function uploadFileWithProgress(
  sdk: YuDreamPluginSdk,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<YuDreamPluginFileObject> {
  if (!sdk.files?.assetUrl) {
    return Promise.reject(new Error('当前宿主未提供文件上传能力'))
  }
  const form = new FormData()
  form.append('file', file)
  form.append('module', 'minecraft-server')
  form.append('publicAccess', 'false')

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    inflightUploads.add(xhr)
    xhr.open('POST', sdk.files.assetUrl('/api/files/upload'))
    xhr.responseType = 'json'
    const token = localStorage.getItem('token')
    if (token) {
      xhr.setRequestHeader('Authorization', token)
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress?.(clampUploadProgress(event.loaded, event.total))
      }
    }
    xhr.onload = () => {
      inflightUploads.delete(xhr)
      try {
        resolve(parseUploadEnvelope(xhr.status, xhr.response))
      }
      catch (error) {
        reject(error)
      }
    }
    xhr.onerror = () => {
      inflightUploads.delete(xhr)
      reject(new Error('网络错误，上传失败'))
    }
    xhr.onabort = () => {
      inflightUploads.delete(xhr)
      reject(new Error('上传已取消'))
    }
    xhr.send(form)
  })
}
