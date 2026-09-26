import { ref } from 'vue'

/**
 * 页面内指标采样环：按时间顺序保留最近 limit 个采样，驱动迷你趋势图。
 * 只在页面打开期间积累（列表页轮询 / 详情页 SSE），不落持久存储；
 * 后端 admin/overview 的 history 可提供跨会话的采样窗口。
 */
export function useMetricRecorder(limit = 60) {
  const values = ref<number[]>([])

  function record(value: number) {
    if (!Number.isFinite(value)) {
      return
    }
    const next = [...values.value, value]
    values.value = next.length > limit ? next.slice(next.length - limit) : next
  }

  function reset() {
    values.value = []
  }

  return { values, record, reset }
}
