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

type MessagingCatalog = {
  connections: () => Promise<ProjectNotificationConnection[]>
}

type UsersCatalog = {
  search: (query?: { keyword?: string, deptId?: string, page?: number, size?: number }) => Promise<ProjectUserOption[]>
  resolve: (ids: string[]) => Promise<ProjectUserOption[]>
  departments: (query?: { keyword?: string, flatten?: boolean }) => Promise<ProjectDeptOption[]>
}

function messagingCatalog(sdk: YuDreamPluginSdk): MessagingCatalog {
  const client = (sdk as YuDreamPluginSdk & { messaging?: MessagingCatalog }).messaging
  if (!client) {
    return { connections: async () => [] }
  }
  return client
}

function usersCatalog(sdk: YuDreamPluginSdk): UsersCatalog {
  const client = (sdk as YuDreamPluginSdk & { users?: UsersCatalog }).users
  if (!client) {
    return {
      search: async () => [],
      resolve: async () => [],
      departments: async () => [],
    }
  }
  return client
}

const PAGE_SIZE = 200

export function createProjectProgressApi(sdk: YuDreamPluginSdk) {
  const messaging = messagingCatalog(sdk)
  const usersCatalogClient = usersCatalog(sdk)
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
    users: async (keyword?: string, deptId?: string) => {
      const records: ProjectUserOption[] = []
      let page = 1
      while (true) {
        const batch = await usersCatalogClient.search({ keyword, deptId, page, size: PAGE_SIZE })
        records.push(...batch)
        if (batch.length < PAGE_SIZE) {
          return records
        }
        page += 1
      }
    },
    usersPage: (keyword?: string, deptId?: string, page = 1, size = 10) => usersCatalogClient.search({ keyword, deptId, page, size }),
    resolveUsers: (ids: string[]) => ids.length ? usersCatalogClient.resolve(ids) : Promise.resolve([]),
    departments: (keyword?: string) => usersCatalogClient.departments({ keyword }),
    minecraftServers: (includeDisabled = false) => sdk.http.get<ProjectMinecraftServerOption[]>(`/admin/minecraft/servers${query({ includeDisabled })}`),
    notificationConnections: () => messaging.connections(),
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
    projectCheckIns: (projectId: string) => getAllPages<ProjectCheckIn>(`/projects/${encodeURIComponent(projectId)}/check-ins`),
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
