import { describe, expect, it, vi } from 'vitest'
import { openAgentMessageStream } from './agent-message-stream'

function fakeFetch(events: string[], authorization?: string) {
  const reader = {
    index: 0,
    async read() {
      if (this.index >= events.length) return { done: true, value: undefined }
      return { done: false, value: new TextEncoder().encode(events[this.index++]) }
    },
  }
  return vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    expect(init?.headers).toMatchObject({ Accept: 'text/event-stream' })
    if (authorization) expect(init?.headers).toMatchObject({ Authorization: authorization })
    return { ok: true, status: 200, body: { getReader: () => reader } } as unknown as Response
  })
}

describe('Agent message event stream', () => {
  it('forwards deltas and resolves after a proposal is ready', async () => {
    vi.stubGlobal('localStorage', { getItem: () => 'super-admin-token' })
    const fetchImpl = fakeFetch(['event: message.delta\ndata: {"delta":"正在分析"}\n\n', 'event: proposal.ready\ndata: {"streamId":"s1","proposal":{"id":"p1"}}\n\n'], 'super-admin-token')
    const onDelta = vi.fn()
    const onProposal = vi.fn()
    const stream = openAgentMessageStream('/events', { onDelta, onProposal }, fetchImpl)

    await expect(stream.done).resolves.toEqual({ kind: 'proposal' })
    expect(onDelta).toHaveBeenCalledWith('正在分析')
    expect(onProposal).toHaveBeenCalledWith(expect.objectContaining({ id: 'p1' }))
    expect(fetchImpl).toHaveBeenCalledOnce()
  })

  it('keeps the readable response when proposal generation only emits a warning', async () => {
    const fetchImpl = fakeFetch(['event: message.complete\ndata: {"content":"内容分析已完成"}\n\n', 'event: proposal.warning\ndata: {"message":"未能生成结构化方案"}\n\n'])
    const onComplete = vi.fn()
    const onWarning = vi.fn()
    const stream = openAgentMessageStream('/events', { onComplete, onWarning }, fetchImpl)

    await expect(stream.done).resolves.toEqual({ kind: 'warning' })
    expect(onComplete).toHaveBeenCalledWith('内容分析已完成')
    expect(onWarning).toHaveBeenCalledWith('未能生成结构化方案')
  })
})
