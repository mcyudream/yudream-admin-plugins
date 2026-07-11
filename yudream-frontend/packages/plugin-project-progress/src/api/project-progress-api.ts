import type {
  ProjectDeptOption,
  ProjectAcceptanceRecord,
  ProjectCheckIn,
  ProjectMemberStats,
  ProjectMinecraftServerOption,
  ProjectNotificationConnection,
  ProjectPersonalStats,
  ProjectProgressEvent,
  ProjectProgressProject,
  ProjectProgressStatus,
  ProjectUserOption,
  ProjectWorkDetail,
} from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

const PAGE_SIZE = 200

export function createProjectProgressApi(sdk: YuDreamPluginSdk) {
  function query(params: Record<string, string | number | boolean | undefined | null>) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        search.set(key, String(value))
      }
    })
    const value = search.toString()
    return value ? `?${value}` : ''
  }

  async function getAllPages<T>(path: string, params: Record<string, string | number | boolean | undefined | null> = {}) {
    const records: T[] = []
    let page = 1
    while (true) {
      const batch = await sdk.http.get<T[]>(`${path}${query({ ...params, page, size: PAGE_SIZE })}`)
      records.push(...batch)
      if (batch.length < PAGE_SIZE) {
        return records
      }
      page += 1
    }
  }

  return {
    status: () => sdk.http.get<ProjectProgressStatus>('/status'),
    projects: () => getAllPages<ProjectProgressProject>('/projects'),
    personalStats: () => sdk.http.get<ProjectPersonalStats>('/me/statistics'),
    projectMemberStats: (projectId: string) => getAllPages<ProjectMemberStats>(`/admin/projects/${encodeURIComponent(projectId)}/member-statistics`),
    users: (keyword?: string, deptId?: string) => getAllPages<ProjectUserOption>('/admin/users', { keyword, deptId }),
    usersPage: (keyword?: string, deptId?: string, page = 1, size = 10) => sdk.http.get<ProjectUserOption[]>(`/admin/users${query({ keyword, deptId, page, size })}`),
    resolveUsers: (ids: string[]) => ids.length
      ? sdk.http.get<ProjectUserOption[]>(`/users/resolve${query({ ids: ids.join(',') })}`)
      : Promise.resolve([]),
    departments: (keyword?: string) => sdk.http.get<ProjectDeptOption[]>(`/admin/departments${query({ keyword })}`),
    minecraftServers: (includeDisabled = false) => sdk.http.get<ProjectMinecraftServerOption[]>(`/admin/minecraft/servers${query({ includeDisabled })}`),
    notificationConnections: () => sdk.http.get<ProjectNotificationConnection[]>('/admin/notification-connections'),
    createProject: (data: Record<string, unknown>) => sdk.http.post<ProjectProgressProject>('/admin/projects', data),
    updateProject: (id: string, data: Record<string, unknown>) => sdk.http.request<ProjectProgressProject>(`/admin/projects/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteProject: (id: string) => sdk.http.request(`/admin/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    details: (projectId: string) => getAllPages<ProjectWorkDetail>(`/projects/${encodeURIComponent(projectId)}/details`),
    createDetail: (projectId: string, data: Record<string, unknown>) => sdk.http.post<ProjectWorkDetail>(`/admin/projects/${encodeURIComponent(projectId)}/details`, data),
    updateDetail: (id: string, data: Record<string, unknown>) => sdk.http.request<ProjectWorkDetail>(`/admin/details/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    deleteDetail: (id: string) => sdk.http.request(`/admin/details/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    publishDetail: (id: string) => sdk.http.post<ProjectWorkDetail>(`/admin/details/${encodeURIComponent(id)}/publish`),
    randomAssign: (id: string) => sdk.http.post<ProjectWorkDetail>(`/admin/details/${encodeURIComponent(id)}/random-assign`),
    claim: (id: string) => sdk.http.post<ProjectWorkDetail>(`/me/tasks/${encodeURIComponent(id)}/claim`),
    claimableTasks: () => getAllPages<ProjectWorkDetail>('/me/tasks/claimable'),
    myTasks: () => getAllPages<ProjectWorkDetail>('/me/tasks'),
    pendingAcceptance: () => getAllPages<ProjectWorkDetail>('/acceptance/pending'),
    submitAcceptance: (detailId: string, data: Record<string, unknown>) => sdk.http.post<ProjectWorkDetail>(`/me/tasks/${encodeURIComponent(detailId)}/submit-acceptance`, data),
    projectCheckIns: (projectId: string) => getAllPages<ProjectCheckIn>(`/admin/projects/${encodeURIComponent(projectId)}/check-ins`),
    rejectCheckIn: (id: string) => sdk.http.post<ProjectCheckIn>(`/admin/check-ins/${encodeURIComponent(id)}/reject`),
    deleteCheckIn: (id: string) => sdk.http.request(`/admin/check-ins/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    myCheckIns: (projectId?: string) => getAllPages<ProjectCheckIn>('/me/check-ins', { projectId }),
    createProjectCheckIn: (projectId: string, data: Record<string, unknown>) => sdk.http.post<ProjectCheckIn>(`/me/projects/${encodeURIComponent(projectId)}/check-ins`, data),
    projectMinecraftCheckIn: (projectId: string) => sdk.http.post<ProjectCheckIn>(`/me/projects/${encodeURIComponent(projectId)}/check-ins/minecraft`),
    autoMinecraftCheckIns: (projectId: string) => sdk.http.post<ProjectCheckIn[]>(`/admin/projects/${encodeURIComponent(projectId)}/minecraft/auto-check-ins`),
    accept: (detailId: string, data: Record<string, unknown>) => sdk.http.post<ProjectAcceptanceRecord>(`/details/${encodeURIComponent(detailId)}/accept`, data),
    reject: (detailId: string, data: Record<string, unknown>) => sdk.http.post<ProjectAcceptanceRecord>(`/details/${encodeURIComponent(detailId)}/reject`, data),
    acceptanceRecords: (detailId: string) => getAllPages<ProjectAcceptanceRecord>(`/details/${encodeURIComponent(detailId)}/acceptance-records`),
    events: (projectId: string, since?: number) => getAllPages<ProjectProgressEvent>(`/projects/${encodeURIComponent(projectId)}/events`, { since }),
    previewFile: (objectKey: string) => sdk.http.blob(`/files/download${query({ key: objectKey, disposition: 'inline' })}`),
    downloadFile: (objectKey: string) => sdk.http.blob(`/files/download${query({ key: objectKey, disposition: 'attachment' })}`),
  }
}
