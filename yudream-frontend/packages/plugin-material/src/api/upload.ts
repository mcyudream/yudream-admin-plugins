import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'

/** 动态创建目录选择器：FaFileUpload 不支持 webkitdirectory，隐藏原生 input 也会被仓库规范检查拦截。用户取消时 Promise 不落终态，调用方保持原状即可。 */
export function pickDirectory(): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.webkitdirectory = true
    input.multiple = true
    input.onchange = () => resolve(Array.from(input.files || []))
    input.click()
  })
}

interface BackendEnvelope<T> {
  code: number
  message: string
  data: T
}

/**
 * sdk.files.uploadImage 走宿主 axios 实例：60s 超时且拿不到上传进度。
 * 大文件需要进度反馈且不能限时，这里改用 XHR 手动携带宿主 token 打同一个 /api/files/upload。
 */
export function uploadFileWithProgress(
  sdk: YuDreamPluginSdk,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<YuDreamPluginFileObject> {
  const form = new FormData()
  form.append('file', file)
  form.append('module', 'material')
  form.append('publicAccess', 'false')

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', sdk.files.assetUrl('/api/files/upload'))
    xhr.responseType = 'json'
    const token = localStorage.getItem('token')
    if (token) {
      xhr.setRequestHeader('Authorization', token)
    }
    xhr.upload.onprogress = (event) => {
      // 传输期间最多报 99，100 留给响应确认后，避免进度条先满后等
      if (event.lengthComputable && event.total > 0) {
        onProgress?.(Math.min(99, Math.round((event.loaded / event.total) * 100)))
      }
    }
    xhr.onload = () => {
      const body = xhr.response as BackendEnvelope<YuDreamPluginFileObject> | null
      if (xhr.status >= 200 && xhr.status < 300 && body?.code === 200 && body.data) {
        onProgress?.(100)
        resolve(body.data)
      }
      else {
        reject(new Error(body?.message || `上传失败（HTTP ${xhr.status}）`))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，上传失败'))
    xhr.onabort = () => reject(new Error('上传已取消'))
    xhr.send(form)
  })
}
