import { decodeBase64ToBytes, EDITOR_TEXT_LIMIT_BYTES, withTimeout } from './fileContent.ts'
import type { FileChunk } from './textFileAccess.ts'

export async function collectFileChunks(fetchChunk: (offset: number, length: number) => Promise<FileChunk>, options: {
  signal: AbortSignal
  maxBytes?: number
  onProgress?: (loaded: number, total: number) => void
}): Promise<Uint8Array[]> {
  const maxBytes = options.maxBytes ?? 128 * 1024 * 1024
  const parts: Uint8Array[] = []
  let offset = 0
  let total: number | undefined
  while (true) {
    options.signal.throwIfAborted()
    const request = withTimeout(fetchChunk(offset, EDITOR_TEXT_LIMIT_BYTES), 40_000, '下载超时，请检查节点连接后重试')
    const chunk = await abortable(request, options.signal)
    const declared = Number(chunk.size)
    if (!Number.isSafeInteger(declared) || declared < 0) throw new Error('节点未返回有效的文件大小')
    if (declared > maxBytes) throw new Error(`网页下载上限为 ${maxBytes / 1024 / 1024} MiB，更大的文件请使用 SFTP`)
    if (total !== undefined && declared !== total) throw new Error('下载期间文件大小发生变化，请重新下载')
    total = declared
    if (typeof chunk.content !== 'string') throw new Error('下载响应缺少文件内容')
    const bytes = decodeBase64ToBytes(chunk.content)
    if (bytes.length > EDITOR_TEXT_LIMIT_BYTES || offset + bytes.length > total) throw new Error('节点返回的分块大小不正确')
    if (bytes.length === 0 && offset < total) throw new Error('节点返回了空分块，下载已停止')
    parts.push(bytes)
    offset += bytes.length
    options.onProgress?.(offset, total)
    if (offset === total) return parts
    if (chunk.eof === true) throw new Error('文件内容不完整，未保存不完整的下载')
  }
}

function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('下载已取消', 'AbortError'))
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
  })
}
