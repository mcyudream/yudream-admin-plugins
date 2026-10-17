import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  Feedback,
  FeedbackPage,
  FeedbackStats,
  FeedbackSubmitPayload,
  FeedbackUserOption,
  FeedbackUserPage,
  FeedbackUserScope,
} from '../types'

/** 用户端接口：只允许调用 /me/**，归属由后端从登录身份推导。 */
export function createFeedbackUserApi(sdk: YuDreamPluginSdk) {
  return {
    list: (params: Record<string, string | number>) =>
      sdk.http.get<FeedbackPage<Feedback>>(`/me/feedbacks?${query(params)}`),
    stats: () => sdk.http.get<FeedbackStats>('/me/stats'),
    detail: (id: string) => sdk.http.get<Feedback>(`/me/feedbacks/${encodeURIComponent(id)}`),
    submit: (data: FeedbackSubmitPayload) => sdk.http.post<Feedback>('/me/feedbacks', data),
    update: (id: string, data: FeedbackSubmitPayload) =>
      sdk.http.request<Feedback>(`/me/feedbacks/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    comment: (id: string, content: string) =>
      sdk.http.post<Feedback>(`/me/feedbacks/${encodeURIComponent(id)}/comments`, { content }),
    /** 归档：管理员给出结论（已解决 / 不予处理）后才允许，归档为终态。 */
    archive: (id: string) =>
      sdk.http.post<Feedback>(`/me/feedbacks/${encodeURIComponent(id)}/archive`, {}),
    withdraw: (id: string) => sdk.http.request(`/me/feedbacks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}

/** 管理端接口：只允许调用 /admin/**，与用户端完全隔离。 */
export function createFeedbackAdminApi(sdk: YuDreamPluginSdk) {
  return {
    list: (params: Record<string, string | number>) =>
      sdk.http.get<FeedbackPage<Feedback>>(`/admin/feedbacks?${query(params)}`),
    stats: () => sdk.http.get<FeedbackStats>('/admin/stats'),
    detail: (id: string) => sdk.http.get<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}`),
    /** 官方回复：写入公开评论，用户可见；后端会顺便把操作者登记为处理人。 */
    reply: (id: string, content: string) =>
      sdk.http.post<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/replies`, { content }),
    /** 内部备注：仅管理员可见；后端同样会把操作者登记为处理人。 */
    note: (id: string, content: string) =>
      sdk.http.post<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/notes`, { content }),
    changeStatus: (id: string, status: string, note: string) =>
      sdk.http.request<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/status`, {
        method: 'PUT',
        data: { status, note },
      }),
    /** 整体替换处理人名单；传空数组表示清空。 */
    assign: (id: string, assigneeIds: string[]) =>
      sdk.http.request<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/assignee`, {
        method: 'PUT',
        data: { assigneeIds },
      }),
    /** 列表操作列的快捷归档：与详情页「流转为已归档」同义，留痕记为归档；不要求先给出结论。 */
    archive: (id: string) =>
      sdk.http.post<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/archive`, {}),
    changeSeverity: (id: string, severity: string) =>
      sdk.http.request<Feedback>(`/admin/feedbacks/${encodeURIComponent(id)}/severity`, {
        method: 'PUT',
        data: { severity },
      }),
    remove: (id: string) => sdk.http.request(`/admin/feedbacks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /**
     * 处理人 / 提交人选择器数据源：只回传账号目录信息，不含任何反馈数据。
     *
     * <p>scope=manage 用于「处理人」侧：返回账号目录候选与 available/notice。宿主 SPI 不提供
     * 按权限反查用户，候选未按管理权限预筛；账号目录不可用时 available=false，前端拒绝选择。
     */
    users: (keyword: string, page: number, size: number, scope: FeedbackUserScope = 'all') =>
      sdk.http.get<FeedbackUserPage>(
        `/admin/users?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}${scope === 'manage' ? '&scope=manage' : ''}`,
      ),
    resolveUsers: (ids: string[]) =>
      ids.length === 0
        ? Promise.resolve({ records: [] as FeedbackUserOption[] })
        : sdk.http.get<{ records: FeedbackUserOption[] }>(
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
