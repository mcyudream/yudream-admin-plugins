import { onUnmounted, ref } from 'vue'

import type { YmclEvent } from '../types'

/**
 * SSE 事件流消费（fetch-stream 模式，可携带 Authorization——EventSource
 * 无法自定义 header）。分帧解析参照 mc-wiki JobLogModal 先例。
 */
export function useEventStream() {
  const events = ref<YmclEvent[]>([])
  const connected = ref(false)
  let controller: AbortController | null = null

  function disconnect() {
    controller?.abort()
    controller = null
    connected.value = false
  }

  async function connect(url: string, token: string) {
    disconnect()
    controller = new AbortController()
    const signal = controller.signal
    try {
      const response = await fetch(url, {
        signal,
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: token } : {}),
        },
      })
      if (!response.ok || !response.body) {
        throw new Error(`连接失败 (${response.status})`)
      }
      connected.value = true
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (!signal.aborted) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split(/\r?\n\r?\n/)
        buffer = frames.pop() || ''
        for (const frame of frames) {
          const data = frame
            .split(/\r?\n/)
            .filter(line => line.startsWith('data:'))
            .map(line => line.slice(5).trimStart())
            .join('\n')
          if (!data) {
            continue
          }
          try {
            events.value.unshift(JSON.parse(data) as YmclEvent)
            if (events.value.length > 200) {
              events.value.length = 200
            }
          }
          catch {
            // 忽略无法解析的帧
          }
        }
      }
    }
    catch {
      // 断连或失败均静默：UI 以 connected 状态呈现
    }
    finally {
      connected.value = false
    }
  }

  onUnmounted(disconnect)

  return { events, connected, connect, disconnect }
}
