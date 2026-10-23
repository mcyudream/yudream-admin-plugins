import { decodeBase64ToBytes, EDITOR_TEXT_LIMIT_BYTES, withTimeout } from './fileContent.ts'
import type { FileChunk } from './textFileAccess.ts'

export async function collectFileChunks(fetchChunk: (offset: number, length: number) => Promise<FileChunk>, options: {
  signal: AbortSignal
  maxBytes?: number
  /** 目录列表给出的文件大小（字节）：用于上限预检、进度与完整性校验；缺省时按累积字节计。 */
  expectedSize?: number
  onProgress?: (loaded: number, total: number) => void
}): Promise<Uint8Array[]> {
  const maxBytes = options.maxBytes ?? 512 * 1024 * 1024
  const expected = options.expectedSize
  if (expected !== undefined && expected > maxBytes) {
    throw new Error(`网页下载上限为 ${maxBytes / 1024 / 1024} MiB，更大的文件请使用 SFTP`)
  }
  const parts: Uint8Array[] = []
  let offset = 0
  while (true) {
    options.signal.throwIfAborted()
    const request = withTimeout(fetchChunk(offset, EDITOR_TEXT_LIMIT_BYTES), 40_000, '下载超时，请检查节点连接后重试')
    const chunk = await abortable(request, options.signal)
    if (typeof chunk.content !== 'string') throw new Error('下载响应缺少文件内容')
    const bytes = decodeBase64ToBytes(chunk.content)
    if (bytes.length > EDITOR_TEXT_LIMIT_BYTES) throw new Error('节点返回的分块大小不正确')
    if (expected !== undefined && offset + bytes.length > expected) throw new Error('下载期间文件大小发生变化，请刷新后重新下载')
    parts.push(bytes)
    offset += bytes.length
    options.onProgress?.(offset, expected ?? offset)
    if (offset > maxBytes) throw new Error(`网页下载上限为 ${maxBytes / 1024 / 1024} MiB，更大的文件请使用 SFTP`)
    // 终止依据是短读/空读（节点按 min(96KiB, 剩余) 回包）；download.chunk 的 size 字段是
    // 本次返回字节数而非文件总大小，eof 在部分节点版本上不可靠，均不能作为终止依据。
    if (bytes.length < EDITOR_TEXT_LIMIT_BYTES) {
      if (expected !== undefined && offset !== expected) {
        throw new Error('下载内容与目录记录的大小不一致（文件可能在下载期间变化），请刷新后重新下载')
      }
      return parts
    }
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
