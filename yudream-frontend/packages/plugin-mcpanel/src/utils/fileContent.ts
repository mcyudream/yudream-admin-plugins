/**
 * 文件内容编解码与超时辅助（纯函数，node --test 可测）。
 * 编辑器/文件页读写通道共用：base64（节点协议编码）与 UTF-8 文本互转、
 * 二进制启发式、编辑上限与面板侧客户端超时竞争。
 * 其他页面（实例详情 / 终端侧栏等）请复用本模块，不要再拷贝内联编码逻辑。
 */

/** 单次整读编辑上限（协议约束：单次文件数据最多 96KiB）。 */
export const EDITOR_TEXT_LIMIT_BYTES = 96 * 1024

/** 节点协议 base64 → 原始字节（atob 失败会抛出，由调用方转为业务错误）。 */
export function decodeBase64ToBytes(content: string): Uint8Array {
  return Uint8Array.from(atob(content), ch => ch.charCodeAt(0))
}

/**
 * 节点协议 base64 → UTF-8 文本。
 * 使用 fatal TextDecoder 严格解码：非法 UTF-8 序列（二进制 / GBK 等旧编码）
 * 会直接抛出 RangeError，由调用方转为"请下载后本地打开"的业务提示，
 * 而不是把 U+FFFD 乱码当正文展示甚至回写。
 */
export function decodeBase64Utf8(content: string): string {
  if (content === '') {
    return ''
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(decodeBase64ToBytes(content))
}

/** UTF-8 文本 → 节点协议 base64。 */
export function encodeUtf8ToBase64(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  const chunk = 0x8000
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk))
  }
  return btoa(binary)
}

/**
 * 宽松 UTF-8 解码（非法序列替换为 U+FFFD，不抛错）。
 * 用于「只看某个 ASCII 键值」的判定类读取（如 eula.txt），
 * 不用于编辑器展示——编辑展示必须走严格解码以免把乱码写回。
 */
export function decodeBase64Utf8Lenient(content: string): string {
  if (content === '') {
    return ''
  }
  return new TextDecoder('utf-8').decode(decodeBase64ToBytes(content))
}

/**
 * eula.txt 判定（.properties 语义）：忽略空行与 #/! 注释行，
 * 同名键取最后一次出现（后写覆盖先写），值大小写不敏感且仅 true 视为已同意。
 */
export function eulaAcceptedFromText(text: string): boolean {
  let accepted = false
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#') || line.startsWith('!')) {
      continue
    }
    const eq = line.indexOf('=')
    if (eq <= 0 || line.slice(0, eq).trim().toLowerCase() !== 'eula') {
      continue
    }
    accepted = line.slice(eq + 1).trim().toLowerCase() === 'true'
  }
  return accepted
}

/** 编辑器支持的显示/保存编码。value 必须是 TextDecoder 认可的 WHATWG label。 */
export interface EditorEncoding {
  value: string
  label: string
}

export const EDITOR_ENCODINGS: EditorEncoding[] = [
  { value: 'utf-8', label: 'UTF-8' },
  { value: 'gbk', label: 'GBK（简体中文）' },
  { value: 'gb18030', label: 'GB18030（简体中文）' },
  { value: 'big5', label: 'Big5（繁体中文）' },
  { value: 'shift_jis', label: 'Shift_JIS（日文）' },
  { value: 'euc-jp', label: 'EUC-JP（日文）' },
  { value: 'windows-1252', label: 'Windows-1252（西欧）' },
  { value: 'iso-8859-1', label: 'ISO-8859-1（拉丁）' },
  { value: 'utf-16le', label: 'UTF-16 LE' },
  { value: 'utf-16be', label: 'UTF-16 BE' },
]

export const EDITOR_DEFAULT_ENCODING = 'utf-8'

export function normalizeEditorEncoding(value: unknown): string {
  const encoding = typeof value === 'string' ? value.trim().toLowerCase() : ''
  return EDITOR_ENCODINGS.some(item => item.value === encoding) ? encoding : EDITOR_DEFAULT_ENCODING
}

/**
 * 以指定编码解码原始字节。
 * UTF-8 保持 fatal 严格解码（非法序列=选错编码或二进制，直接抛错）；
 * 旧编码非 fatal（单字节族天然完备，多字节遗留编码遇坏序列以 U+FFFD 标记，
 * 由 looksLikeBinary 兜底识别误开二进制）。
 */
export function decodeBytesWithEncoding(bytes: Uint8Array, encoding: string, stream = false): string {
  const label = normalizeEditorEncoding(encoding)
  return new TextDecoder(label, { fatal: label === 'utf-8', ignoreBOM: true }).decode(bytes, { stream })
}

/** 旧编码写入的字节数粗估（精确值由面板转码后以协议约束兜底）。 */
export function estimateEncodedByteLength(text: string, encoding: string): number {
  const label = normalizeEditorEncoding(encoding)
  if (label === 'utf-16le' || label === 'utf-16be') {
    return text.length * 2 + 2
  }
  return utf8ByteLength(text)
}

/** 文本对应的 UTF-8 字节数（判断编辑上限用，不等价于字符串长度）。 */
export function utf8ByteLength(text: string): number {
  return new TextEncoder().encode(text).length
}

/** 给慢 Promise 竞争一个客户端超时：超时抛出 message，先完成则透传。 */
export function withTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), timeoutMs)
  })
  return Promise.race([
    promise.finally(() => {
      if (timer !== undefined) {
        clearTimeout(timer)
      }
    }),
    timeout,
  ])
}

/**
 * 启发式判断文本是否为二进制内容（JAR/图片等）：出现 NUL 字节，
 * 或 U+FFFD 替换符占比过高（UTF-8 解码失败的信号）。
 * 用于把"二进制文件当文本读"的场景转为明确提示，而不是展示乱码。
 */
export function looksLikeBinary(text: string): boolean {
  const sample = text.slice(0, 8000)
  if (sample.includes('\0')) {
    return true
  }
  let bad = 0
  const check = 600
  for (let index = 0; index < Math.min(sample.length, check); index++) {
    const code = sample.charCodeAt(index)
    if (code === 0xfffd || (code < 32 && code !== 9 && code !== 10 && code !== 13)) {
      bad++
    }
  }
  return bad > check * 0.1
}

/**
 * 编辑上限判断（基于节点报告的原始文件 size，而不是预览文本长度）：
 * size 未知（undefined/非法值）时返回 false，交由读取通道按协议约束兜底。
 */
export function exceedsEditorLimit(size?: number | string | null): boolean {
  const value = typeof size === 'number' ? size : Number(size)
  return Number.isFinite(value) && value > EDITOR_TEXT_LIMIT_BYTES
}
