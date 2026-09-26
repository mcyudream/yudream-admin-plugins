import { decodeBase64ToBytes, decodeBytesWithEncoding, EDITOR_TEXT_LIMIT_BYTES, encodeUtf8ToBase64, estimateEncodedByteLength, looksLikeBinary, normalizeEditorEncoding, withTimeout } from './fileContent.ts'

export interface FileChunk {
  content?: string
  size?: number | string
  eof?: boolean
}

/** 读取通道的原始结果：未解码的字节（base64）+ 大小 + 截断标记，解码由标签层按所选编码进行。 */
export interface RawFileContent {
  rawBase64: string
  size: number
  truncated: boolean
}

export interface TextFileContent extends RawFileContent {
  text: string
}

/**
 * 校验并取出节点分块响应的原始字节，不做文本解码：
 * 字节原样保留给标签层，切换显示编码时无需重新读取。
 */
export function readRawChunk(chunk: FileChunk): RawFileContent {
  if (typeof chunk.content !== 'string') throw new Error('节点未返回文件内容，请重新读取')
  const size = Number(chunk.size)
  if (!Number.isSafeInteger(size) || size < 0) throw new Error('节点未返回有效文件大小，无法安全编辑')
  const bytes = decodeBase64ToBytes(chunk.content)
  if (bytes.length > EDITOR_TEXT_LIMIT_BYTES || bytes.length > size) throw new Error('文件分块响应大小不正确')
  const truncated = chunk.eof === false || size > bytes.length
  if (!truncated && size !== bytes.length) throw new Error('文件内容不完整，请重新读取')
  if (size > 0 && bytes.length === 0) throw new Error('节点返回了空的文件分块，请重新读取')
  return { rawBase64: chunk.content, size, truncated }
}

/**
 * 以指定编码解码原始读取结果。UTF-8 严格校验；非法 UTF-8 抛出可提示换编码的错误。
 * 预览可能止于多字节字符中间；仅在确认有后续字节时允许保留未完成序列。
 */
export function decodeRawText(raw: RawFileContent, encoding: string): string {
  const label = normalizeEditorEncoding(encoding)
  const bytes = decodeBase64ToBytes(raw.rawBase64)
  let text: string
  try {
    text = decodeBytesWithEncoding(bytes, label, raw.truncated)
  }
  catch {
    throw new Error(`此文件不是有效的 ${label.toUpperCase()} 文本，请选择其他编码重新打开`)
  }
  if (looksLikeBinary(text)) throw new Error('二进制文件不能在线编辑，请下载后打开')
  return text
}

export function decodeTextChunk(chunk: FileChunk, encoding = 'utf-8'): TextFileContent {
  const raw = readRawChunk(chunk)
  return { ...raw, text: decodeRawText(raw, encoding) }
}

export function createTextFileAccess(options: {
  readChunk: (path: string, offset: number, length: number) => Promise<unknown>
  /** content 为 UTF-8 文本的 base64；charset 非 UTF-8 时由面板侧转码后落盘。 */
  write: (path: string, content: string, charset: string) => Promise<unknown>
  readTimeoutMs?: number
  saveTimeoutMs?: number
}) {
  return {
    async load(path: string): Promise<RawFileContent> {
      const result = await withTimeout(options.readChunk(path, 0, EDITOR_TEXT_LIMIT_BYTES),
        options.readTimeoutMs ?? 12_000, '读取超时，请检查节点连接后重试。不会自动重复请求。')
      return readRawChunk(result as FileChunk)
    },
    async save(path: string, text: string, charset = 'utf-8') {
      const label = normalizeEditorEncoding(charset)
      if (estimateEncodedByteLength(text, label) > EDITOR_TEXT_LIMIT_BYTES) {
        throw new Error(`内容按 ${label.toUpperCase()} 编码超过 96 KiB 在线保存上限，请使用 SFTP`)
      }
      await withTimeout(options.write(path, encodeUtf8ToBase64(text), label), options.saveTimeoutMs ?? 40_000,
        '保存结果未确认：请求已超时，但节点可能仍在写入。请先重新核对文件，不要连续重复保存。')
    },
  }
}

export function relativeFilePath(value: unknown): string {
  const path = typeof value === 'string' ? value : ''
  if (path.includes('\\') || path.includes('\0') || path.split('/').includes('..')) return ''
  return path.replace(/^\/+|\/+$/g, '')
}
