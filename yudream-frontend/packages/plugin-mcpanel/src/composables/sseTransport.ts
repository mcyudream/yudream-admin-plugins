import type { SseFrame } from './sse-frames.ts'
import { extractCompleteFrames, parseSseFrame } from './sse-frames.ts'

export type SseErrorReason = 'auth' | 'protocol' | 'network'
export interface SseTransportCallbacks {
  onConnecting?(): void
  onOpen?(): void
  onFrame?(frame: SseFrame): void
  onEnded?(): boolean
  onError?(reason: SseErrorReason): void
  onClosed?(): void
}
export interface SseTransportDeps {
  fetchImpl?: typeof fetch
  getToken?: () => string | null
  backoffBaseMs?: number
  maxBackoffMs?: number
  connectionTimeoutMs?: number
  idleTimeoutMs?: number
}
export interface SseTransportClient {
  start(url: string): void
  stop(): void
  isTerminal(): boolean
}
export class SseProtocolError extends Error {}

function untilAbort<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('连接已取消', 'AbortError'))
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
    operation.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
  })
}

export function createSseTransport(callbacks: SseTransportCallbacks, deps: SseTransportDeps = {}): SseTransportClient {
  const fetchImpl = deps.fetchImpl ?? fetch
  const base = deps.backoffBaseMs ?? 1000
  const max = deps.maxBackoffMs ?? 15_000
  let generation = 0
  let stopped = true
  let terminal = false
  let currentUrl = ''
  let controller: AbortController | null = null
  let cancelDelay: (() => void) | null = null
  const active = (epoch: number) => !stopped && generation === epoch

  function clear(notify: boolean) {
    generation++
    stopped = true
    cancelDelay?.()
    cancelDelay = null
    controller?.abort()
    controller = null
    if (notify) callbacks.onClosed?.()
  }
  function delay(ms: number) {
    return new Promise<void>((resolve) => {
      const finish = () => { clearTimeout(timer); cancelDelay = null; resolve() }
      const timer = setTimeout(finish, ms)
      cancelDelay = finish
    })
  }
  async function run(epoch: number, url: string) {
    let backoff = base
    while (active(epoch)) {
      const local = new AbortController()
      controller = local
      let reader: ReadableStreamDefaultReader<Uint8Array> | null = null
      let timer: ReturnType<typeof setTimeout> | undefined
      let timedOut = false
      const arm = (ms: number) => {
        clearTimeout(timer)
        timer = setTimeout(() => { timedOut = true; local.abort() }, ms)
      }
      callbacks.onConnecting?.()
      try {
        arm(deps.connectionTimeoutMs ?? 15_000)
        const token = deps.getToken?.()
        const fetching = fetchImpl(url, { signal: local.signal, headers: { Accept: 'text/event-stream', ...(token ? { Authorization: token } : {}) } })
        void fetching.then(response => {
          if (!active(epoch) || local.signal.aborted) void response.body?.cancel().catch(() => {})
        }, () => {})
        const response = await untilAbort(fetching, local.signal)
        if (!active(epoch)) return
        if (response.status === 401 || response.status === 403) {
          terminal = true
          stopped = true
          void response.body?.cancel().catch(() => {})
          callbacks.onError?.('auth')
          return
        }
        if (!response.ok || !response.body) {
          void response.body?.cancel().catch(() => {})
          throw new Error(`HTTP ${response.status}`)
        }
        if (!response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
          void response.body.cancel().catch(() => {})
          throw new SseProtocolError('事件流格式不正确')
        }
        clearTimeout(timer)
        callbacks.onOpen?.()
        if (!active(epoch)) return
        reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        while (active(epoch)) {
          arm(deps.idleTimeoutMs ?? 65_000)
          const result = await untilAbort(reader.read(), local.signal)
          if (!active(epoch)) return
          if (result.done) break
          backoff = base
          const extracted = extractCompleteFrames(buffer, decoder.decode(result.value, { stream: true }))
          buffer = extracted.rest
          for (const raw of extracted.frames) {
            if (!active(epoch)) return
            const frame = parseSseFrame(raw)
            if (frame) callbacks.onFrame?.(frame)
          }
        }
        if (!active(epoch)) return
        if (callbacks.onEnded?.() === false) { stopped = true; return }
        callbacks.onError?.('network')
      }
      catch (error) {
        if (!active(epoch)) return
        if (!local.signal.aborted || timedOut) callbacks.onError?.(error instanceof SseProtocolError ? 'protocol' : 'network')
      }
      finally {
        clearTimeout(timer)
        if (reader) {
          void reader.cancel().catch(() => {})
          try { reader.releaseLock() } catch { /* 已取消的流可能已释放 reader。 */ }
        }
        local.abort()
        if (controller === local) controller = null
      }
      if (!active(epoch)) return
      await delay(backoff)
      backoff = Math.min(max, backoff * 2)
    }
  }
  return {
    start(url) {
      if (!stopped && url === currentUrl) return
      clear(false)
      terminal = false
      stopped = false
      currentUrl = url
      void run(generation, url)
    },
    stop: () => clear(true),
    isTerminal: () => terminal,
  }
}
