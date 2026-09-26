/**
 * SSE 帧解析与 mcpanel 事件解包分类。纯函数、无 Vue/浏览器依赖，供
 * nodeEventsStream / sseTransport 消费并可在 `node --test` 下回归。
 *
 * 约束：业务事件 data 为 { id, type, at, payload } envelope；严格 id 匹配
 * （payload 中的资源键必须与期望值完全一致）才放行。connected/心跳等
 * 其他任何事件一律忽略，不得当作业务数据。
 */

export interface SseFrame {
  event?: string
  data: string
}

/** 服务端队列溢出通知（后端约定 envelope { id,type,at,payload:{dropped,...} }）。 */
export const STREAM_GAP_EVENT = 'stream.gap'

export type NodeEventKind = 'node.stats' | 'node.state'

export interface ClassifiedNodeEvent {
  kind: NodeEventKind
  payload: Record<string, unknown>
}

/** 解析一个完整 SSE 帧（以空行分隔的原始文本）。无 data 行返回 null。 */
export function parseSseFrame(raw: string): SseFrame | null {
  let eventName: string | undefined
  const dataLines: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    }
    else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
    // id:/retry:/注释行与 M1 无关，忽略
  }
  if (!dataLines.length) {
    return null
  }
  return { event: eventName, data: dataLines.join('\n') }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 严格分类节点事件：event 名限定两种、JSON 必须为对象 envelope、payload.nodeId
 * 与期望节点完全一致；任何不满足都返回 null（调用方直接丢弃该帧）。
 */
export function classifyNodeEvent(frame: SseFrame, expectedNodeId: string): ClassifiedNodeEvent | null {
  if (frame.event !== 'node.stats' && frame.event !== 'node.state') {
    return null
  }
  let envelope: unknown
  try {
    envelope = JSON.parse(frame.data)
  }
  catch {
    return null
  }
  if (!isPlainObject(envelope)) {
    return null
  }
  const payload = envelope.payload
  if (!isPlainObject(payload)) {
    return null
  }
  if (typeof payload.nodeId !== 'string' || payload.nodeId !== expectedNodeId) {
    return null
  }
  return { kind: frame.event, payload }
}

/**
 * 把新到的 chunk 并入缓冲并取走全部完整帧。缓冲无界增长（始终没有空行分隔的
 * 异常流）超过上限时整体丢弃；超过单帧上限的「完整帧」也整帧拒绝派发，
 * 保住内存上界。
 */
export function extractCompleteFrames(buffer: string, chunk: string, maxBufferLength = 1 << 20): { frames: string[], rest: string } {
  const merged = buffer + chunk
  if (merged.length > maxBufferLength && !merged.includes('\n')) {
    return { frames: [], rest: '' }
  }
  const parts = merged.split(/\r?\n\r?\n/)
  const rest = parts.pop() ?? ''
  const frames = parts.filter(frame => frame.length <= maxBufferLength)
  if (rest.length > maxBufferLength) {
    // 未闭合的超长帧不可信，丢弃
    return { frames, rest: '' }
  }
  return { frames, rest }
}

// ---------- 流式 envelope 解包（终端输出 / 实例输出等过滤流共用） ----------

export interface UnwrappedStreamEvent {
  /** 业务事件名（如 node.terminal.output / instance.output）。 */
  event: string
  payload: Record<string, unknown>
  /** true = stream.gap 队列溢出通知（输出存在丢失），非业务输出。 */
  gap: boolean
}

export interface UnwrapStreamOptions {
  /** 允许的业务事件名白名单（如 ['node.terminal.output']）。 */
  events: readonly string[]
  /** 严格匹配的资源键（'terminalId' / 'instanceId'）。 */
  idKey: string
  /** 期望的资源 id；不匹配的帧整帧丢弃（防串扰）。 */
  expectedId: string
  /**
   * 期望的节点 id；提供时业务帧与 stream.gap 帧的 payload.nodeId 都必须与
   * 之严格一致（缺失即丢弃，宁弃不收）。
   */
  nodeId?: string
}

/**
 * 严格解包过滤流事件：event 必须在白名单或为 stream.gap；data 必须是
 * { id, type, at, payload } 对象 envelope。归属判定（防串扰，宁弃不收）：
 * - 业务事件：payload[idKey] 必须存在且与 expectedId 严格一致；
 * - stream.gap（payload 形如 { nodeId, topic, dropped, reason, filter? }）：
 *   payload[idKey] 存在时必须一致；否则读 payload.filter——filter.key 与订阅键
 *   一致时 String(filter.value) 必须等于 expectedId；两者都缺省视为本节点广播。
 * - 提供 nodeId 时：所有帧（业务与 gap）的 payload.nodeId 必须严格一致。
 * 任何不满足都返回 null，调用方直接丢弃该帧。
 */
export function unwrapStreamEvent(frame: SseFrame, options: UnwrapStreamOptions): UnwrappedStreamEvent | null {
  const isGap = frame.event === STREAM_GAP_EVENT
  if (!isGap && !options.events.includes(frame.event ?? '')) {
    return null
  }
  let envelope: unknown
  try {
    envelope = JSON.parse(frame.data)
  }
  catch {
    return null
  }
  if (!isPlainObject(envelope)) {
    return null
  }
  const payload = envelope.payload
  if (!isPlainObject(payload)) {
    return null
  }
  if (options.nodeId !== undefined) {
    const eventNodeId = payload.nodeId
    if (typeof eventNodeId !== 'string' || eventNodeId !== options.nodeId) {
      return null
    }
  }
  if (isGap) {
    if (!gapMatchesSubscription(payload, options)) {
      return null
    }
  }
  else {
    const actualId = payload[options.idKey]
    if (actualId === undefined || actualId === null || String(actualId) !== options.expectedId) {
      return null
    }
  }
  return { event: frame.event ?? '', payload, gap: isGap }
}

/** stream.gap 归属判定：见 unwrapStreamEvent 注释。 */
function gapMatchesSubscription(payload: Record<string, unknown>, options: UnwrapStreamOptions): boolean {
  const direct = payload[options.idKey]
  if (direct !== undefined && direct !== null) {
    return String(direct) === options.expectedId
  }
  const filter = payload.filter
  if (isPlainObject(filter)) {
    const { key, value } = filter
    if (typeof key === 'string' && key === options.idKey) {
      return value !== undefined && value !== null && String(value) === options.expectedId
    }
    // filter 指向其他订阅键：与本订阅无关。
    return false
  }
  if (payload.filterKey !== undefined) {
    return payload.filterKey === options.idKey && payload.filterValue === options.expectedId
  }
  return false
}
