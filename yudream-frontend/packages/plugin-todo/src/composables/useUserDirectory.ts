import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { ref } from 'vue'
import { createTodoAdminApi, createTodoUserApi } from '../api/todo-api'
import { userDisplayName } from './utils'

/**
 * 用户昵称缓存：参与人只回传 ID，展示时按 ID 批量回查昵称。
 *
 * <p>用户端与管理端走各自的接口（/me/** 与 /admin/**），不能共用同一份请求通道。
 */
export function useUserDirectory(sdk: YuDreamPluginSdk, surface: 'user' | 'admin') {
  const names = ref<Record<string, string>>({})
  const api = surface === 'admin' ? createTodoAdminApi(sdk) : createTodoUserApi(sdk)

  /** 批量补齐缺失的昵称；失败时按 ID 展示，不阻断列表渲染。 */
  async function ensure(ids: string[]) {
    const missing = [...new Set(ids.filter(id => !!id && !names.value[id]))]
    if (missing.length === 0) {
      return
    }
    try {
      const response = await api.resolveUsers(missing)
      const next = { ...names.value }
      for (const user of response.records ?? []) {
        next[user.id] = userDisplayName(user)
      }
      names.value = next
    }
    catch {
      // 回查失败保留 ID 展示
    }
  }

  function labels(ids: string[]): string[] {
    return ids.map(id => names.value[id] ?? id)
  }

  return { names, ensure, labels }
}
