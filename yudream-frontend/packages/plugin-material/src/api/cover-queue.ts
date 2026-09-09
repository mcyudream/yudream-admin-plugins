/** Firefox 每域名约 6 条并发连接；库页封面必须排队，避免占满后把列表/筛选请求挤掉。 */
const MAX_INFLIGHT = 2
let inflight = 0
const waiters: Array<() => void> = []

export function acquireCoverSlot(): Promise<() => void> {
  return new Promise((resolve) => {
    const grant = () => {
      if (inflight >= MAX_INFLIGHT) {
        waiters.push(grant)
        return
      }
      inflight += 1
      let released = false
      resolve(() => {
        if (released) {
          return
        }
        released = true
        inflight = Math.max(0, inflight - 1)
        const next = waiters.shift()
        next?.()
      })
    }
    grant()
  })
}
