import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { ref } from 'vue'
import { createMcInteropApi } from '../api/mc-interop-api'

/** 各页面共享的加载/错误骨架：页面级状态由各自 composable 持有。 */
export function useMcInteropShell(sdk: YuDreamPluginSdk) {
  const api = createMcInteropApi(sdk)
  const loading = ref(false)
  const error = ref('')

  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    loading.value = true
    error.value = ''
    try {
      return await action()
    }
    catch (cause) {
      error.value = cause instanceof Error ? cause.message : String(cause)
      return undefined
    }
    finally {
      loading.value = false
    }
  }

  return { api, loading, error, run }
}

/** 宿主把 Long 序列化为数字字符串；epoch 毫秒转本地时间文本。 */
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
