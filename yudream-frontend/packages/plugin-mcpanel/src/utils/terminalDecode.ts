import { decodeBase64ToBytes, normalizeEditorEncoding } from './fileContent.ts'

/**
 * 终端/日志输出解码器（实例控制台与节点终端共用）。
 *
 * 节点事件同时携带 text（JSON 字符串，非法 UTF-8 已被节点替换为 U+FFFD 的兜底
 * 可读视图）与 data（原始字节 base64，0.3.1+）。本解码器优先取 data 按当前编码
 * 流式解码——跨帧半个多字节字符由 TextDecoder stream 模式保持完整；
 * 旧节点没有 data 字段时原样回退 text。
 *
 * 纯 TS、无浏览器/Vue 依赖，`node --test` 下回归（terminalDecode.test.ts）。
 */
export interface TerminalDecoder {
  /** 实时帧：data 优先流式解码；无 data 回退 text。 */
  decodeFrame: (data: unknown, fallbackText: unknown) => string
  /** 历史快照：单块完整解码（尾部可能截断在多字节字符中间，宽松处理）。 */
  decodeSnapshot: (data: unknown, fallbackText: unknown) => string
  /** 流重连/会话切换：丢弃半个多字节序列的解码状态。 */
  reset: () => void
}

export function createTerminalDecoder(getEncoding: () => string): TerminalDecoder {
  let label = ''
  let decoder: TextDecoder | null = null

  function streaming() {
    const next = normalizeEditorEncoding(getEncoding())
    if (!decoder || label !== next) {
      label = next
      // 终端流永不因坏字节中断：非法序列以 U+FFFD 呈现。
      decoder = new TextDecoder(label, { fatal: false })
    }
    return decoder
  }

  return {
    decodeFrame(data, fallbackText) {
      if (typeof data === 'string' && data.length) {
        return streaming().decode(decodeBase64ToBytes(data), { stream: true })
      }
      return typeof fallbackText === 'string' ? fallbackText : ''
    },
    decodeSnapshot(data, fallbackText) {
      if (typeof data === 'string' && data.length) {
        return new TextDecoder(normalizeEditorEncoding(getEncoding()), { fatal: false })
          .decode(decodeBase64ToBytes(data))
      }
      return typeof fallbackText === 'string' ? fallbackText : ''
    },
    reset() {
      decoder = null
    },
  }
}
