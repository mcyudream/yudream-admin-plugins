import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast } from '@yudream/components'
import { ref } from 'vue'
import { createUnionApi } from '../api/union-api'

/** 各页面共享的加载/错误/toast 骨架：页面级状态由各自 composable 持有。 */
export function useUnionApiShell(sdk: YuDreamPluginSdk) {
  const api = createUnionApi(sdk)
  const toast = useYdToast()
  const loading = ref(false)
  const error = ref('')

  async function run(action: () => Promise<void>) {
    loading.value = true
    error.value = ''
    try {
      await action()
    }
    catch (cause) {
      error.value = errorMessageOf(cause)
    }
    finally {
      loading.value = false
    }
  }

  function fail(cause: unknown, fallback: string) {
    toast.error(cause instanceof Error ? cause.message : fallback)
  }

  return { api, toast, loading, error, run, fail }
}

/**
 * 宿主 axios 对非 2xx 会 reject 携带响应体的错误：后端业务错误体带 message 字段，
 * 优先取它，避免把 axios 英文默认文案当错误展示。
 */
export function errorMessageOf(cause: unknown): string {
  const data = (cause as { response?: { data?: { message?: string } } } | null)?.response?.data
  const backend = typeof data?.message === 'string' ? data.message : ''
  return backend || (cause instanceof Error ? cause.message : String(cause))
}

/** 宿主把 Long 序列化为数字字符串；epoch 毫秒（数字或数字字符串）转本地时间文本。 */
export function formatDateTime(value?: number | string | null): string {
  if (value == null || value === '') {
    return '-'
  }
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return '-'
  }
  return new Date(numeric).toLocaleString('zh-CN', { hour12: false })
}
