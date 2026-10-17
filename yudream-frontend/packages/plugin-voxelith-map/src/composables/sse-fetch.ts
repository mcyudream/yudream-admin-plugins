/**
 * 带鉴权的 SSE 流式消费。
 *
 * 原生 EventSource 无法携带 Authorization 头，而宿主 sa-token 只认 header——
 * 对带权限的端点（如 /admin/tasks/events）会直接回 400 并在控制台刷错误，
 * 这正是后端 VoxelithEventStream javadoc 里写明「不能用 EventSource」的原因。
 *
 * 改用 fetch + ReadableStream 手工解析 SSE，与宿主 plugin-devtools 的
 * consumeSseStream 同一模式：令牌直接读宿主登录态落盘的 localStorage 'token'
 * （插件 remote 模块与宿主同源，读得到；宿主写入的是裸 token，无 Bearer 前缀）。
 *
 * 事件块以空行分隔，形如：
 *   event: task
 *   data: {"id":"..."}
 */
export async function consumeSseStream(
  url: string,
  signal: AbortSignal,
  onEvent: (event: string, data: string) => void,
): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  const token = localStorage.getItem('token')
  if (token)
    headers.Authorization = token

  const response = await fetch(url, { headers, signal })
  if (!response.ok || !response.body) {
    throw new Error(`事件流连接失败（HTTP ${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  function emitBlock(block: string): void {
    let eventName = ''
    const dataLines: string[] = []
    for (const rawLine of block.split('\n')) {
      const line = rawLine.trimEnd()
      if (!line || line.startsWith(':'))
        continue
      if (line.startsWith('event:'))
        eventName = line.slice('event:'.length).trim()
      else if (line.startsWith('data:'))
        dataLines.push(line.slice('data:'.length).trimStart())
    }
    if (dataLines.length > 0)
      onEvent(eventName, dataLines.join('\n'))
  }

  function drain(complete = false): void {
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      emitBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (complete && buffer.trim()) {
      emitBlock(buffer)
      buffer = ''
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done)
      break
    buffer += decoder.decode(value, { stream: true })
    drain()
  }
  buffer += decoder.decode()
  drain(true)
}
