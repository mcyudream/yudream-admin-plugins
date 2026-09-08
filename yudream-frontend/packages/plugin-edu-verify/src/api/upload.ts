import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { errorMessage } from '../types'

const MAX_BYTES = 8 * 1024 * 1024
const ALLOWED_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'application/pdf',
])
const ALLOWED_EXT = /\.(jpe?g|png|gif|webp|pdf)$/i

function fileToBase64(file: File, onProgress?: (percent: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress?.(Math.min(90, Math.round((event.loaded / event.total) * 90)))
      }
    }
    reader.onload = () => {
      const result = String(reader.result || '')
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

export function assertUploadable(file: File) {
  if (!file || file.size <= 0) {
    throw new Error('请选择有效文件')
  }
  if (file.size > MAX_BYTES) {
    throw new Error('单份材料不能超过 8MB')
  }
  const type = (file.type || '').toLowerCase()
  const name = file.name || ''
  if ((type && !ALLOWED_TYPES.has(type) && type !== 'application/octet-stream') || !ALLOWED_EXT.test(name)) {
    throw new Error('仅支持 JPEG/PNG/GIF/WebP 图片或 PDF')
  }
}

export async function uploadFileWithProgress(
  sdk: YuDreamPluginSdk,
  file: File,
  email: string,
  onProgress?: (percent: number) => void,
): Promise<YuDreamPluginFileObject> {
  assertUploadable(file)
  const contact = email.trim()
  if (!contact) {
    throw new Error('请先填写联系邮箱再上传材料')
  }
  const content = await fileToBase64(file, onProgress)
  onProgress?.(92)
  try {
    const data = await sdk.http.post<YuDreamPluginFileObject>('/public/manual/upload', {
      email: contact,
      filename: file.name,
      contentType: file.type || 'application/octet-stream',
      content,
    })
    onProgress?.(100)
    return data
  }
  catch (cause) {
    throw new Error(errorMessage(cause, '上传失败'))
  }
}
