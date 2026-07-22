import type { AgentProposal } from '../types'

interface AgentMessageStreamHandlers {
  onStart?: (streamId: string, sessionId: string) => void
  onDelta?: (delta: string) => void
  onComplete?: (content: string) => void
  onProposal?: (proposal: AgentProposal) => void
  onWarning?: (message: string) => void
}

type AgentMessageStreamResult = { kind: 'proposal' | 'warning' }
type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

function parseEvent(chunk: string): { name: string; data: string } | null {
  let name = 'message'
  const data: string[] = []
  chunk.split(/\r?\n/).forEach(line => {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  })
  return data.length ? { name, data: data.join('\n') } : null
}

function authHeaders(): HeadersInit {
  const token = typeof localStorage === 'undefined' ? '' : localStorage.getItem('token')
  return token ? { Authorization: token, Accept: 'text/event-stream' } : { Accept: 'text/event-stream' }
}

export function openAgentMessageStream(
  url: string,
  handlers: AgentMessageStreamHandlers,
  fetchImpl: FetchLike = (input, init) => fetch(input, init),
) {
  const controller = new AbortController()
  let settled = false
  let resolveDone!: (result: AgentMessageStreamResult) => void
  let rejectDone!: (error: Error) => void
  const done = new Promise<AgentMessageStreamResult>((resolve, reject) => {
    resolveDone = resolve
    rejectDone = reject
  })

  const close = () => controller.abort()
  const finish = (result: AgentMessageStreamResult) => {
    if (settled) return
    settled = true
    close()
    resolveDone(result)
  }
  const fail = (message: string) => {
    if (settled) return
    settled = true
    close()
    rejectDone(new Error(message || 'Agent 流式响应失败'))
  }
  const handle = (chunk: string) => {
    const event = parseEvent(chunk)
    if (!event) return
    try {
      const data = JSON.parse(event.data) as Record<string, any>
      if (event.name === 'message.start') handlers.onStart?.(data.streamId, data.sessionId)
      else if (event.name === 'message.delta') handlers.onDelta?.(data.delta ?? '')
      else if (event.name === 'message.complete') handlers.onComplete?.(data.content ?? '')
      else if (event.name === 'proposal.ready') {
        handlers.onProposal?.(data.proposal)
        finish({ kind: 'proposal' })
      }
      else if (event.name === 'proposal.warning') {
        handlers.onWarning?.(data.message ?? '')
        finish({ kind: 'warning' })
      }
      else if (event.name === 'message.error') fail(data.message ?? 'Agent 流式响应失败')
    }
    catch (error) {
      fail(error instanceof Error ? error.message : 'Agent 流式事件格式无效')
    }
  }

  void (async () => {
    try {
      const response = await fetchImpl(url, { headers: authHeaders(), signal: controller.signal })
      if (!response.ok || !response.body) {
        fail(`Agent 流式连接失败 (${response.status})`)
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (!settled) {
        const { done: finished, value } = await reader.read()
        if (finished) break
        buffer += decoder.decode(value, { stream: true })
        const chunks = buffer.split(/\r?\n\r?\n/)
        buffer = chunks.pop() || ''
        chunks.forEach(handle)
      }
      if (!settled && buffer.trim()) handle(buffer)
      if (!settled && !controller.signal.aborted) fail('Agent 流式连接已中断')
    }
    catch (error) {
      if (!controller.signal.aborted) fail(error instanceof Error ? error.message : 'Agent 流式连接失败')
    }
  })()

  return { close, done }
}
