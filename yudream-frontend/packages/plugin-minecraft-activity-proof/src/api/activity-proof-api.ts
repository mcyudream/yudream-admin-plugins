import type {
  Activity,
  ActivityDeptOption,
  ActivityFormOption,
  ActivityParticipantAdmin,
  ActivityProofExportRecord,
  ActivityProofMapping,
  ActivityProofServer,
  ActivityProofSettings,
  ActivityProofStatus,
  ActivityProofTemplate,
  ActivityQqConnection,
  ActivityQqGroup,
  ActivityQuizCategoryOption,
  ActivityQuizConfig,
  ActivityQuizView,
  ActivityTemplateMembers,
  ActivityUserOption,
  ActivityVerifyResult,
  MyParticipation,
  PageResult,
  ServerParticipantSyncResult,
  UserActivity,
} from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export interface ActivityQuery {
  keyword?: string
  status?: string
  page?: number
  size?: number
}

export interface ActivityBindingPayload {
  type: string
  serverId?: string
  minOnlineMinutes?: number
  includeAfk?: boolean
  autoJoin?: boolean
  formCode?: string
}

export interface ActivitySavePayload {
  id?: string
  title: string
  summary: string
  description: string
  coverUrl: string
  signupStart?: number
  signupEnd?: number
  activityStart?: number
  activityEnd?: number
  deptMode: string
  allowedDeptIds: string[]
  bindings: ActivityBindingPayload[]
}

export interface ActivityExportPayload {
  activityId: string
  proofNo: string
  college: string
  issuer: string
  issueDate: string
  selectedUserIds?: string[]
}

export interface ActivityParticipantAddPayload {
  userId: string
  passed?: boolean
  note?: string
}

export interface ActivityQuizConfigPayload {
  enabled: boolean
  categoryId?: string
  tags?: string[]
  types?: string[]
  difficulties?: number[]
  count?: number
  passCorrect?: number
  subjectiveMode?: string
}

export function createActivityProofApi(sdk: YuDreamPluginSdk) {
  function query(params: Record<string, string | number | boolean | undefined>) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        search.set(key, String(value))
      }
    })
    const value = search.toString()
    return value ? `?${value}` : ''
  }

  const admin = {
    status: () => sdk.http.get<ActivityProofStatus>('/admin/status'),
    servers: () => sdk.http.get<ActivityProofServer[]>('/admin/servers'),
    settings: () => sdk.http.get<ActivityProofSettings>('/admin/settings'),
    saveSettings: (data: Record<string, unknown>) => sdk.http.request<ActivityProofSettings>('/admin/settings', { method: 'PUT', data }),
    templates: (keyword = '', page = 1, size = 200) => sdk.http.get<ActivityProofTemplate[]>(`/admin/templates${query({ keyword, page, size })}`),
    selectTemplate: (templateId: string) => sdk.http.request<ActivityProofSettings>('/admin/template', { method: 'PUT', data: { templateId } }),
    departments: (keyword = '') => sdk.http.get<ActivityDeptOption[]>(`/admin/departments${query({ keyword })}`),
    forms: (keyword = '', page = 1, size = 200) => sdk.http.get<ActivityFormOption[]>(`/admin/forms${query({ keyword, page, size })}`),
    userOptions: (keyword = '', page = 1, size = 20) => sdk.http.get<PageResult<ActivityUserOption>>(`/admin/user-options${query({ keyword, page, size })}`),
    templateMembers: (templateId: string) => sdk.http.get<ActivityTemplateMembers>(`/admin/template-members${query({ templateId })}`),
    saveTemplateMembers: (data: { templateId: string, userIds: string[] }) => sdk.http.request<ActivityTemplateMembers>('/admin/template-members', { method: 'PUT', data }),
    qqConnections: () => sdk.http.get<ActivityQqConnection[]>('/admin/qq/connections'),
    qqGroups: (connectionId: string) => sdk.http.get<ActivityQqGroup[]>(`/admin/qq/groups${query({ connectionId })}`),
    activities: (params: ActivityQuery = {}) => sdk.http.get<PageResult<Activity>>(`/admin/activities${query({ keyword: params.keyword, status: params.status, page: params.page ?? 1, size: params.size ?? 10 })}`),
    activity: (id: string) => sdk.http.get<Activity>(`/admin/activities/${encodeURIComponent(id)}`),
    createActivity: (data: ActivitySavePayload) => sdk.http.post<Activity>('/admin/activities', data),
    updateActivity: (id: string, data: ActivitySavePayload) => sdk.http.request<Activity>(`/admin/activities/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    publishActivity: (id: string) => sdk.http.post<Activity>(`/admin/activities/${encodeURIComponent(id)}/publish`),
    closeActivity: (id: string) => sdk.http.post<Activity>(`/admin/activities/${encodeURIComponent(id)}/close`),
    deleteActivity: (id: string) => sdk.http.request(`/admin/activities/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    participants: (activityId: string, page = 1, size = 10) => sdk.http.get<PageResult<ActivityParticipantAdmin>>(`/admin/activities/${encodeURIComponent(activityId)}/participants${query({ page, size })}`),
    addParticipant: (activityId: string, data: ActivityParticipantAddPayload) => sdk.http.post<ActivityParticipantAdmin>(`/admin/activities/${encodeURIComponent(activityId)}/participants`, data),
    removeParticipant: (activityId: string, userId: string) => sdk.http.request(`/admin/activities/${encodeURIComponent(activityId)}/participants/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
    verifyParticipant: (activityId: string, userId: string) => sdk.http.post<ActivityParticipantAdmin>(`/admin/activities/${encodeURIComponent(activityId)}/participants/${encodeURIComponent(userId)}/verify`),
    verifyAllParticipants: (activityId: string) => sdk.http.post<ActivityVerifyResult>(`/admin/activities/${encodeURIComponent(activityId)}/verify-all`),
    syncServerParticipants: (activityId: string) => sdk.http.post<ServerParticipantSyncResult>(`/admin/activities/${encodeURIComponent(activityId)}/sync-server-participants`),
    mappings: (serverId = '', page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofMapping>>(`/admin/mappings${query({ serverId, page, size })}`),
    saveMapping: (data: Record<string, unknown>) => sdk.http.request<ActivityProofMapping>('/admin/mappings', { method: 'PUT', data }),
    deleteMapping: (id: string) => sdk.http.request(`/admin/mappings/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    exportWord: (data: ActivityExportPayload) => sdk.http.post<ActivityProofExportRecord>('/admin/exports', data),
    exports: (page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofExportRecord>>(`/admin/exports${query({ page, size })}`),
    uploadStampedPdf: (id: string, data: Record<string, unknown>) => sdk.http.request<ActivityProofExportRecord>(`/admin/exports/${encodeURIComponent(id)}/stamped-pdf`, { method: 'PUT', data }),
    deleteExport: (id: string) => sdk.http.request(`/admin/exports/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    quizConfig: (activityId: string) => sdk.http.get<ActivityQuizConfig>(`/admin/activities/${encodeURIComponent(activityId)}/quiz`),
    saveQuizConfig: (activityId: string, data: ActivityQuizConfigPayload) => sdk.http.request<ActivityQuizConfig>(`/admin/activities/${encodeURIComponent(activityId)}/quiz`, { method: 'PUT', data }),
    quizCategoryOptions: () => sdk.http.get<ActivityQuizCategoryOption[]>('/admin/quiz-options/categories'),
  }

  const me = {
    activities: (page = 1, size = 12) => sdk.http.get<PageResult<UserActivity>>(`/me/activities${query({ page, size })}`),
    activity: (id: string) => sdk.http.get<UserActivity>(`/me/activities/${encodeURIComponent(id)}`),
    join: (id: string) => sdk.http.post<UserActivity>(`/me/activities/${encodeURIComponent(id)}/join`),
    cancel: (id: string) => sdk.http.post<UserActivity>(`/me/activities/${encodeURIComponent(id)}/cancel`),
    participations: (page = 1, size = 10) => sdk.http.get<PageResult<MyParticipation>>(`/me/participations${query({ page, size })}`),
    verify: (activityId: string) => sdk.http.post<MyParticipation>(`/me/participations/${encodeURIComponent(activityId)}/verify`),
    exports: (page = 1, size = 10) => sdk.http.get<PageResult<ActivityProofExportRecord>>(`/me/exports${query({ page, size })}`),
    quiz: (activityId: string) => sdk.http.get<ActivityQuizView>(`/me/activities/${encodeURIComponent(activityId)}/quiz`),
    startQuiz: (activityId: string) => sdk.http.post<ActivityQuizView>(`/me/activities/${encodeURIComponent(activityId)}/quiz/attempt`),
  }

  return {
    admin,
    me,
    download: (path: string) => sdk.http.blob(path),
    downloadUrl: (path: string) => sdk.http.url(path),
  }
}

export type ActivityProofApi = ReturnType<typeof createActivityProofApi>
