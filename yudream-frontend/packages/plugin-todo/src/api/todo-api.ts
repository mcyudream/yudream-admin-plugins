import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  TodoAdminStats,
  TodoDeptOption,
  TodoFromTemplatePayload,
  TodoOverview,
  TodoPage,
  TodoSchedule,
  TodoTask,
  TodoTaskPayload,
  TodoTemplate,
  TodoUserOption,
} from '../types'

/** 用户端接口：只允许调用 /me/**，归属由后端从登录身份推导。 */
export function createTodoUserApi(sdk: YuDreamPluginSdk) {
  return {
    overview: () => sdk.http.get<TodoOverview>('/me/overview'),
    tasks: (params: Record<string, string | number>) => sdk.http.get<TodoPage<TodoTask>>(`/me/tasks?${query(params)}`),
    create: (data: TodoTaskPayload) => sdk.http.post<TodoTask>('/me/tasks', data),
    update: (id: string, data: TodoTaskPayload) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    leave: (id: string) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/leave`, { method: 'PUT' }),
    setArchived: (id: string, archived: boolean) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/archive`, { method: 'PUT', data: { archived } }),
    setDone: (id: string, done: boolean) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/status`, { method: 'PUT', data: { done } }),
    setSubTaskDone: (id: string, subTaskId: string, done: boolean) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/sub-tasks/${encodeURIComponent(subTaskId)}`, {
        method: 'PUT',
        data: { done },
      }),
    /** 加入小任务：把自己加入该小任务的负责人列表。 */
    joinSubTask: (id: string, subTaskId: string) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/sub-tasks/${encodeURIComponent(subTaskId)}/join`, {
        method: 'PUT',
      }),
    /** 退出小任务：把自己从该小任务的负责人列表移除。 */
    leaveSubTask: (id: string, subTaskId: string) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(id)}/sub-tasks/${encodeURIComponent(subTaskId)}/leave`, {
        method: 'PUT',
      }),
    remove: (id: string) => sdk.http.request(`/me/tasks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    templates: (keyword: string) =>
      sdk.http.get<{ records: TodoTemplate[] }>(`/me/templates?keyword=${encodeURIComponent(keyword)}`),
    createFromTemplate: (data: TodoFromTemplatePayload) => sdk.http.post<TodoTask>('/me/tasks/from-template', data),
    /** 从任务库加入一条已有任务：把当前登录者加入参与人，不新建副本。 */
    joinTask: (taskId: string) =>
      sdk.http.request<TodoTask>(`/me/tasks/${encodeURIComponent(taskId)}/join`, { method: 'PUT' }),
    schedule: (from: number, to: number) => sdk.http.get<TodoSchedule>(`/me/schedule?from=${from}&to=${to}`),
    /** 当前用户加入的部门选项：任务库可见范围选择器数据源。 */
    departments: () => sdk.http.get<{ records: TodoDeptOption[] }>('/me/departments'),
    /** 参与人检索：只回传账号目录信息，不含任何待办数据。 */
    users: (keyword: string, page: number, size: number) =>
      sdk.http.get<{ records: TodoUserOption[] }>(
        `/me/users?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`,
      ),
    resolveUsers: (ids: string[]) =>
      ids.length === 0
        ? Promise.resolve({ records: [] as TodoUserOption[] })
        : sdk.http.get<{ records: TodoUserOption[] }>(
            `/me/users/resolve?${ids.map(id => `ids=${encodeURIComponent(id)}`).join('&')}`,
          ),
  }
}

/** 管理员端接口：只允许调用 /admin/**，与用户端完全隔离。 */
export function createTodoAdminApi(sdk: YuDreamPluginSdk) {
  return {
    tasks: (params: Record<string, string | number>) =>
      sdk.http.get<TodoPage<TodoTask>>(`/admin/tasks?${query(params)}`),
    stats: () => sdk.http.get<TodoAdminStats>('/admin/stats'),
    /** 全量部门树拍平选项：管理端可见范围选择器数据源。 */
    departments: (keyword?: string) =>
      sdk.http.get<{ records: TodoDeptOption[] }>(
        `/admin/departments${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`,
      ),
    update: (id: string, data: TodoTaskPayload) =>
      sdk.http.request<TodoTask>(`/admin/tasks/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    remove: (id: string) => sdk.http.request(`/admin/tasks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    templates: (keyword: string) =>
      sdk.http.get<{ records: TodoTemplate[] }>(`/admin/templates?keyword=${encodeURIComponent(keyword)}`),
    users: (keyword: string, page: number, size: number) =>
      sdk.http.get<{ records: TodoUserOption[] }>(
        `/admin/users?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`,
      ),
    resolveUsers: (ids: string[]) =>
      ids.length === 0
        ? Promise.resolve({ records: [] as TodoUserOption[] })
        : sdk.http.get<{ records: TodoUserOption[] }>(
            `/admin/users/resolve?${ids.map(id => `ids=${encodeURIComponent(id)}`).join('&')}`,
          ),
  }
}

function query(params: Record<string, string | number>): string {
  return Object.entries(params)
    .filter(([, value]) => value !== '' && value !== null && value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&')
}
