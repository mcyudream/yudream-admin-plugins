import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoUserOption } from '../types'
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
  /** 同一次回查的完整档案（头像/邮箱等）：peek 卡的展示信息直接取这里，不再为卡片单独请求。 */
  const users = ref<Record<string, TodoUserOption>>({})
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
      const nextUsers = { ...users.value }
      for (const user of response.records ?? []) {
        next[user.id] = userDisplayName(user)
        nextUsers[user.id] = user
      }
      names.value = next
      users.value = nextUsers
    }
    catch {
      // 回查失败保留 ID 展示
    }
  }

  function labels(ids: string[]): string[] {
    return ids.map(id => names.value[id] ?? id)
  }

  /** 已回查的用户档案；未回查到返回 undefined，调用方按本地行内信息兜底。 */
  function option(id: string): TodoUserOption | undefined {
    return users.value[id]
  }

  return { names, ensure, labels, option }
}
