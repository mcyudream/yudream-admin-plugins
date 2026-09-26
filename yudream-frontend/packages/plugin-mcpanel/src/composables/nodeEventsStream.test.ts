import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { classifyNodeEvent, extractCompleteFrames, parseSseFrame } from './sse-frames.ts'
import { createNodeEventsStream } from './nodeEventsStream.ts'

/**
 * SSE 解析 / 事件分类 / 事件流客户端（取消、切换、重连、终态）回归测试。
 * 纯 node:test 运行：`pnpm --filter @yudream/plugin-mcpanel test`，不引入测试框架依赖。
 */

const NODE_ID = '1937096012345678901'

function statsEnvelope(nodeId: string, cpuPercent = 11) {
  return JSON.stringify({ id: '01J', type: 'node.stats', at: 1, payload: { nodeId, cpuPercent } })
}

function stateEnvelope(nodeId: string, status: string) {
  return JSON.stringify({ id: '01J', type: 'node.state', at: 1, payload: { nodeId, status } })
}

function sseFrame(event: string, data: string) {
  return `event: ${event}\ndata: ${data}\n\n`
}

function textStream(chunks: string[], options: { hold?: boolean, onCancel?: () => void } = {}) {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      if (!options.hold) {
        controller.close()
      }
    },
    cancel() {
      options.onCancel?.()
    },
  })
}

function sseResponse(body: ReadableStream<Uint8Array>, status = 200, contentType = 'text/event-stream') {
  return new Response(body, { status, headers: { 'content-type': contentType } })
}

interface ClientHarness {
  events: Array<{ kind: string, nodeId?: string }>
  opens: number
  errors: string[]
  closed: number
  fetchCalls: string[]
  client: ReturnType<typeof createNodeEventsStream>
}

function createHarness(fetchImpl: typeof fetch): ClientHarness {
  const harness: ClientHarness = { events: [], opens: 0, errors: [], closed: 0, fetchCalls: [], client: null as never }
  harness.client = createNodeEventsStream({
    onOpen: () => {
      harness.opens += 1
    },
    onError: (reason) => {
      harness.errors.push(reason)
    },
    onClosed: () => {
      harness.closed += 1
    },
    onEvent: (event) => {
      harness.events.push({ kind: event.kind, nodeId: (event.payload as { nodeId?: string }).nodeId })
    },
  }, { fetchImpl, backoffBaseMs: 1, maxBackoffMs: 2 })
  return harness
}

const tick = (ms = 10) => new Promise<void>(resolve => setTimeout(resolve, ms))

describe('parseSseFrame', () => {
  it('提取 event 与多行 data，忽略 id/retry/注释', () => {
    const frame = parseSseFrame('event: node.stats\r\nid: 42\r\n: keep-alive\r\ndata: {"a":1}\r\ndata: {"b":2}\n\n')
    assert.deepEqual(frame, { event: 'node.stats', data: '{"a":1}\n{"b":2}' })
  })

  it('无 data 行返回 null', () => {
    assert.equal(parseSseFrame('event: ping\n\n'), null)
  })
})

describe('classifyNodeEvent', () => {
  it('放行 node.stats / node.state 并解出 payload', () => {
    const stats = classifyNodeEvent({ event: 'node.stats', data: statsEnvelope(NODE_ID) }, NODE_ID)
    assert.equal(stats?.kind, 'node.stats')
    assert.equal((stats?.payload as { cpuPercent?: number }).cpuPercent, 11)
    const state = classifyNodeEvent({ event: 'node.state', data: stateEnvelope(NODE_ID, 'online') }, NODE_ID)
    assert.equal(state?.kind, 'node.state')
    assert.equal((state?.payload as { status?: string }).status, 'online')
  })

  it('拒绝未知事件、缺 event、坏 JSON、非对象 payload', () => {
    assert.equal(classifyNodeEvent({ event: 'connected', data: '{"ok":true}' }, NODE_ID), null)
    assert.equal(classifyNodeEvent({ event: 'heartbeat', data: '{}', }, NODE_ID), null)
    assert.equal(classifyNodeEvent({ data: statsEnvelope(NODE_ID) }, NODE_ID), null)
    assert.equal(classifyNodeEvent({ event: 'node.stats', data: 'not-json' }, NODE_ID), null)
    assert.equal(classifyNodeEvent({ event: 'node.stats', data: '[1,2]' }, NODE_ID), null)
  })

  it('payload.nodeId 与当前节点不一致时拒绝（防串扰）', () => {
    assert.equal(classifyNodeEvent({ event: 'node.stats', data: statsEnvelope('other-node') }, NODE_ID), null)
    assert.equal(classifyNodeEvent({ event: 'node.stats', data: '{"payload":{}}' }, NODE_ID), null)
  })
})

describe('extractCompleteFrames', () => {
  it('切出完整帧并保留未闭合尾巴', () => {
    const { frames, rest } = extractCompleteFrames('', `a\n\nb\n\nc`)
    assert.deepEqual(frames, ['a', 'b'])
    assert.equal(rest, 'c')
  })

  it('无界垃圾流超限被整体丢弃', () => {
    const { frames, rest } = extractCompleteFrames('', 'x'.repeat((1 << 20) + 1))
    assert.deepEqual(frames, [])
    assert.equal(rest, '')
  })

  it('完整单帧超限同样整帧拒绝，不影响前后正常帧', () => {
    const oversized = 'x'.repeat(40)
    const { frames, rest } = extractCompleteFrames('', `ok\n\n${oversized}\n\nafter`, 16)
    assert.deepEqual(frames, ['ok'])
    assert.equal(rest, 'after')
  })
})

describe('createNodeEventsStream', () => {
  it('严格派发 node.stats/node.state，忽略心跳与 connected 帧', { timeout: 5000 }, async (t) => {
    // 服务端正常关闭会被视为断线并重连（生产语义）；这里 hold 住流保证确定性
    const body = textStream([
      sseFrame('connected', '{"ok":true}'),
      sseFrame('node.stats', statsEnvelope(NODE_ID, 21)),
      sseFrame('heartbeat', '{}'),
      sseFrame('node.stats', statsEnvelope('other-node', 99)),
      sseFrame('node.state', stateEnvelope(NODE_ID, 'online')),
      'data: not-json\n\n',
    ], { hold: true })
    const harness = createHarness(() => Promise.resolve(sseResponse(body)))
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick()
    assert.deepEqual(harness.events, [
      { kind: 'node.stats', nodeId: NODE_ID },
      { kind: 'node.state', nodeId: NODE_ID },
    ])
    assert.equal(harness.opens, 1)
  })

  it('stop() 立即取消 reader 且不悬挂、之后不再派发', { timeout: 5000 }, async (t) => {
    const encoder = new TextEncoder()
    // TS 无法追踪 ReadableStream start 回调里的赋值，用 sink 中转
    const sink: { push?: (chunk: Uint8Array) => void } = {}
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(sseFrame('node.stats', statsEnvelope(NODE_ID))))
        sink.push = chunk => controller.enqueue(chunk)
      },
    })
    const harness = createHarness(() => Promise.resolve(sseResponse(stream)))
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick()
    assert.equal(harness.events.length, 1)
    // 被测行为本体：主动 stop；fake 流不可被 signal 中断，靠 stop 的 abort+cancel 退出读循环
    harness.client.stop()
    await tick()
    // stop 后迟到的帧不得再派发（取消生效且不悬挂——本测试能结束即为证）
    assert.throws(() => sink.push?.(encoder.encode(sseFrame('node.stats', statsEnvelope(NODE_ID, 42)))), /closed/)
    await tick()
    assert.equal(harness.events.length, 1)
  })

  it('切换节点 URL：旧代际事件被隔离，不污染新节点', { timeout: 5000 }, async (t) => {
    const encoder = new TextEncoder()
    const sink: { push?: (chunk: Uint8Array) => void } = {}
    const streamA = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(sseFrame('node.stats', statsEnvelope('node-A'))))
        sink.push = chunk => controller.enqueue(chunk)
      },
    })
    const streamB = textStream([sseFrame('node.stats', statsEnvelope('node-B', 5))], { hold: true })
    const harness = createHarness((url) => {
      harness.fetchCalls.push(String(url))
      return Promise.resolve(String(url).includes('node-a') ? sseResponse(streamA) : sseResponse(streamB))
    })
    harness.client.start('sse://events/node-a', 'node-A')
    t.after(() => harness.client.stop())
    await tick()
    assert.deepEqual(harness.events, [{ kind: 'node.stats', nodeId: 'node-A' }])

    harness.client.start('sse://events/node-b', 'node-B')
    await tick()
    // 旧 reader 已被真正取消，旧流拒绝新数据，且不会污染新节点。
    assert.throws(() => sink.push?.(encoder.encode(sseFrame('node.stats', statsEnvelope('node-A', 77)))), /closed/)
    await tick()
    assert.deepEqual(harness.events, [
      { kind: 'node.stats', nodeId: 'node-A' },
      { kind: 'node.stats', nodeId: 'node-B' },
    ])
  })

  it('401 为终态：只请求一次、不再重试且保留 error 终态（不被 closed 覆盖）', { timeout: 5000 }, async (t) => {
    const harness = createHarness((url) => {
      harness.fetchCalls.push(String(url))
      return Promise.resolve(new Response(null, { status: 401 }))
    })
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick(30)
    assert.equal(harness.fetchCalls.length, 1)
    assert.deepEqual(harness.errors, ['auth'])
    // 启动和鉴权失败均不派发虚假的 closed 状态。
    assert.equal(harness.closed, 0)
    assert.equal(harness.client.isTerminal(), true)
  })

  it('服务端正常关闭立即给出断开状态并重连恢复', { timeout: 5000 }, async (t) => {
    const harness = createHarness((url) => {
      harness.fetchCalls.push(String(url))
      if (harness.fetchCalls.length === 1) {
        // 第一条连接发送一帧后由服务端正常关闭（EOF）
        return Promise.resolve(sseResponse(textStream([sseFrame('node.stats', statsEnvelope(NODE_ID, 9))])))
      }
      return Promise.resolve(sseResponse(textStream([sseFrame('node.stats', statsEnvelope(NODE_ID, 10))], { hold: true })))
    })
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick(50)
    assert.deepEqual(harness.events, [
      { kind: 'node.stats', nodeId: NODE_ID },
      { kind: 'node.stats', nodeId: NODE_ID },
    ])
    assert.deepEqual(harness.errors, ['network'])
    assert.equal(harness.opens, 2)
  })

  it('Content-Type 非 event-stream 视为协议错误并退避重试成功', { timeout: 5000 }, async (t) => {
    let bodyReleased = false
    const harness = createHarness((url) => {
      harness.fetchCalls.push(String(url))
      if (harness.fetchCalls.length === 1) {
        return Promise.resolve(sseResponse(textStream(['<html>gateway</html>'], { onCancel: () => { bodyReleased = true } }), 200, 'text/html'))
      }
      return Promise.resolve(sseResponse(textStream([sseFrame('node.stats', statsEnvelope(NODE_ID, 3))], { hold: true })))
    })
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick(50)
    assert.equal(harness.fetchCalls.length, 2)
    assert.deepEqual(harness.errors, ['protocol'])
    assert.equal(bodyReleased, true)
    assert.deepEqual(harness.events, [{ kind: 'node.stats', nodeId: NODE_ID }])
  })

  it('网络错误后退避重连并恢复事件流', { timeout: 5000 }, async (t) => {
    const harness = createHarness((url) => {
      harness.fetchCalls.push(String(url))
      if (harness.fetchCalls.length === 1) {
        return Promise.reject(new TypeError('fetch failed'))
      }
      return Promise.resolve(sseResponse(textStream([sseFrame('node.stats', statsEnvelope(NODE_ID, 7))], { hold: true })))
    })
    harness.client.start('sse://nodes/x/events', NODE_ID)
    t.after(() => harness.client.stop())
    await tick(50)
    assert.equal(harness.fetchCalls.length, 2)
    assert.deepEqual(harness.errors, ['network'])
    assert.deepEqual(harness.events, [{ kind: 'node.stats', nodeId: NODE_ID }])
  })
})
