import type { McpNodeStateEvent, McpNodeStats } from '../types'
import { classifyNodeEvent } from './sse-frames.ts'
import { createSseTransport } from './sseTransport.ts'
import type { SseTransportDeps } from './sseTransport.ts'

/**
 * 节点事件流客户端（node.stats / node.state）。纯 TS、无 Vue 依赖。
 *
 * 传输细节（裸 token、Content-Type 校验、连接/空闲 deadline、401/403 终态、
 * 退避、代际隔离、reader cancel+release）统一由共享 sseTransport 提供；
 * 这里只做节点事件的严格分类与编排，行为与旧实现保持一致：
 * - 每次 start(url, nodeId) 为独立代际；旧代际回调、迟到帧不污染新代际；
 * - stop() 立即中断退避等待并取消 reader，绝不悬挂；
 * - 心跳/connected/其他节点事件一律忽略，不得覆盖当前快照。
 */

export type StreamErrorReason = 'auth' | 'protocol' | 'network'

export interface NodeStreamEvent {
  kind: 'node.stats' | 'node.state'
  payload: McpNodeStats | McpNodeStateEvent
}

export interface NodeEventsClientCallbacks {
  onConnecting?(): void
  onOpen?(): void
  onError?(reason: StreamErrorReason): void
  onClosed?(): void
  onEvent?(event: NodeStreamEvent): void
}

export interface NodeEventsClientDeps extends SseTransportDeps {
}

export interface NodeEventsClient {
  start(url: string, expectedNodeId: string): void
  stop(): void
  /** 401/403 终态后为 true：继续重连无意义。 */
  isTerminal(): boolean
}

export function createNodeEventsStream(callbacks: NodeEventsClientCallbacks, deps: NodeEventsClientDeps = {}): NodeEventsClient {
  let expectedNodeId = ''
  // EOF（服务端正常关闭）沿用旧语义：视为断线并重连。
  const transport = createSseTransport({
    onConnecting: callbacks.onConnecting,
    onOpen: callbacks.onOpen,
    onEnded: () => true,
    onError: callbacks.onError,
    onClosed: callbacks.onClosed,
    onFrame: (frame) => {
      const classified = classifyNodeEvent(frame, expectedNodeId)
      if (!classified) {
        return // 心跳/connected/其他节点事件，不得覆盖当前快照
      }
      callbacks.onEvent?.(classified as NodeStreamEvent)
    },
  }, deps)

  return {
    start(url: string, nodeId: string) {
      expectedNodeId = nodeId
      transport.start(url)
    },
    stop: () => transport.stop(),
    isTerminal: () => transport.isTerminal(),
  }
}
