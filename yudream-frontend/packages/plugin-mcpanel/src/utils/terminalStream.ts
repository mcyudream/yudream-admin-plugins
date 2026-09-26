/**
 * 终端原始输出装配器：把跨 chunk 到达的 PTY/日志文本按完整行切分。
 *
 * 背景：SSE 帧 / 轮询块的边界与换行无关——半行、甚至 ANSI 转义序列都可能被
 * 切在两个 chunk 里。逐 chunk `split('\n')` 会把半行当独立行渲染出乱码与残码。
 * 这里维护一个未闭合行缓冲：只有遇到真实换行（\n）才成行，跨块字节保持拼接，
 * ANSI 序列因此天然完整；流结束时 flush 兜底输出最后一行。
 *
 * 纯 TS、无浏览器/Vue 依赖，`node --test` 下回归（terminalStream.test.ts）。
 */

export interface RawLineAssemblerOptions {
  /** 每凑成一个完整行回调一次（行内不含换行；行尾 \r 已剥除）。 */
  onLine: (text: string) => void
  /**
   * 未闭合行缓冲上限（字符数，默认 64KiB）。异常无换行流超限时把缓冲
   * 强制成行并清空，保证内存有界。
   */
  maxPendingLength?: number
}

export interface RawLineAssembler {
  /** 追加一个文本块（任意切分粒度）。 */
  push(chunk: string): void
  /** 流结束：把未闭合的半行作为最后一行输出。 */
  flush(): void
  /** 丢弃未闭合缓冲（清屏/切换会话时）；不影响已输出行。 */
  reset(): void
  /** 当前未闭合缓冲长度（测试/诊断用）。 */
  pendingLength(): number
  pendingText(): string
}

const DEFAULT_MAX_PENDING = 64 * 1024

export function createRawLineAssembler(options: RawLineAssemblerOptions): RawLineAssembler {
  const maxPendingLength = options.maxPendingLength ?? DEFAULT_MAX_PENDING
  let pending = ''

  function emit(text: string) {
    // 行尾单个 \r（CRLF 的前半）剥除；其余 \r 属于行内容保留。
    const line = text.endsWith('\r') ? text.slice(0, -1) : text
    options.onLine(line)
  }

  return {
    push(chunk: string) {
      if (!chunk) {
        return
      }
      pending += chunk
      let index = pending.indexOf('\n')
      while (index >= 0) {
        const line = pending.slice(0, index)
        pending = pending.slice(index + 1)
        emit(line)
        index = pending.indexOf('\n')
      }
      if (pending.length > maxPendingLength) {
        // 病态无换行流：强制成行，保住内存上界（宁可断行不累积）。
        emit(pending)
        pending = ''
      }
    },
    flush() {
      if (pending.length) {
        const line = pending
        pending = ''
        emit(line)
      }
    },
    reset() {
      pending = ''
    },
    pendingLength: () => pending.length,
    pendingText: () => pending,
  }
}
