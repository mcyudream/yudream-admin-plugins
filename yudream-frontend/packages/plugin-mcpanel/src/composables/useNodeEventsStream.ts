import type { McpNodeStateEvent, McpNodeStats, McpStreamState } from '../types'
import { onUnmounted, ref } from 'vue'
import { createNodeEventsStream } from './nodeEventsStream'

export interface UseNodeEventsStreamOptions {
  onStats?: (stats: McpNodeStats) => void
  onState?: (event: McpNodeStateEvent) => void
}

/**
 * 节点事件流 composable：连接状态、最新快照与最新状态事件的响应式封装。
 * start/stop 都会清空 latestStats/latestState，杜绝上一个节点的数据残留；
 * 组件卸载自动 stop。
 */
export function useNodeEventsStream(options: UseNodeEventsStreamOptions = {}) {
  const state = ref<McpStreamState>('closed')
  const latestStats = ref<McpNodeStats | null>(null)
  const latestState = ref<McpNodeStateEvent | null>(null)
  const frames = ref(0)
  const authFailed = ref(false)

  const client = createNodeEventsStream({
    onConnecting: () => {
      state.value = 'connecting'
    },
    onOpen: () => {
      state.value = 'open'
    },
    onError: (reason) => {
      state.value = 'error'
      if (reason === 'auth') {
        authFailed.value = true
      }
    },
    onClosed: () => {
      state.value = 'closed'
    },
    onEvent: (event) => {
      frames.value += 1
      if (event.kind === 'node.stats') {
        const stats = event.payload as McpNodeStats
        latestStats.value = stats
        options.onStats?.(stats)
      }
      else {
        const stateEvent = event.payload as McpNodeStateEvent
        latestState.value = stateEvent
        options.onState?.(stateEvent)
      }
    },
  }, {
    getToken: () => localStorage.getItem('token'),
  })

  function reset() {
    latestStats.value = null
    latestState.value = null
    frames.value = 0
    authFailed.value = false
  }

  function start(url: string, nodeId: string) {
    reset()
    client.start(url, nodeId)
  }

  /** 统一事件流模式：不自建连接，只登记期望节点并复位快照（帧由外部传输层 ingest）。 */
  function begin(nodeId: string) {
    reset()
    client.setExpectedNodeId(nodeId)
  }

  function ingest(frame: unknown): boolean {
    return client.ingest(frame)
  }

  function stop() {
    client.stop()
    reset()
  }

  onUnmounted(() => {
    client.stop()
  })

  return { state, latestStats, latestState, frames, authFailed, start, stop, begin, ingest }
}
