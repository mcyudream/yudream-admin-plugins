export interface ProjectProgressStatus {
  minecraftReady: boolean
  mailReady: boolean
}

export interface ProjectUserOption {
  id: string
  username: string
  nickname?: string
  email?: string
  avatar?: string
  status?: string
  deptIds: string[]
  deptNames: string[]
}

export interface ProjectDeptOption {
  id: string
  name: string
  parentId?: string | null
  status?: string
  children: ProjectDeptOption[]
}

/** 一台下游子服；只有群组服代理条目才有，单机服为空数组。 */
export interface ProjectMinecraftSubServer {
  name: string
  address: string
  online: number
  sensor: boolean
  defaultServer: boolean
  sort: number
}

export interface ProjectMinecraftServerOption {
  id: string
  name: string
  enabled: boolean
  currentSeasonId?: string
  currentSeasonName?: string
  /** 该服务器的下游子服；界面据此决定要不要显示子服选择。 */
  subServers?: ProjectMinecraftSubServer[]
}

export interface ProjectNotificationConnection {
  id: string
  name: string
  platform?: string | null
  userId?: string | null
  protocol?: string | null
}

export interface ProjectStatusOption {
  code: string
  label: string
  terminal: boolean
  sort: number
}

export interface ProjectMinecraftPolicy {
  enabled: boolean
  serverId: string
  /** 空表示整服口径（不限子服）；仅在群组服下才可能有值。 */
  subServer: string
  requiredOnlineMinutes: number
  includeAfk: boolean
  autoCheckInEnabled: boolean
}

export interface ProjectProgressProject {
  id: string
  name: string
  description: string
  managerUserIds: string[]
  memberUserIds: string[]
  statuses: ProjectStatusOption[]
  defaultStatusCode: string
  doneStatusCode: string
  reworkStatusCode: string
  minCheckInIntervalMinutes: number
  allowedCheckInTypes: string[]
  minecraftPolicy: ProjectMinecraftPolicy
  notificationConnectionId?: string | null
  notificationChannelId: string
  enabled: boolean
  createdAt: number
  updatedAt: number
}

export interface ProjectFileEvidence {
  objectKey: string
  filename: string
  contentType: string
  size: number
  image: boolean
}

export interface ProjectWorkDetail {
  id: string
  projectId: string
  title: string
  description: string
  statusCode: string
  assignmentMode: 'CLAIM' | 'RANDOM'
  requiredAssigneeCount: number
  candidateUserIds: string[]
  assigneeUserIds: string[]
  acceptorUserIds: string[]
  published: boolean
  pendingAcceptance: boolean
  acceptanceSummary: string
  acceptanceFiles: ProjectFileEvidence[]
  dueAt?: number | null
  createdAt: number
  updatedAt: number
}

export interface ProjectMinecraftSubServerEvidence {
  name: string
  onlineMillis: number
  afkMillis: number
}

export interface ProjectCheckIn {
  id: string
  projectId: string
  detailId: string
  userId: string
  type: string
  summary: string
  files: ProjectFileEvidence[]
  location?: {
    address: string
    latitude?: number
    longitude?: number
  } | null
  minecraft?: {
    serverId: string
    playerId: string
    playerName: string
    /**
     * 本次判定所用的子服口径；空表示整服（把该玩家在这台服全部子服上的时长相加）。
     *
     * 与 subServers 是两件事：这里回答「这次按哪台算」，subServers 回答「时间分布在哪几台」。
     */
    subServer?: string
    totalOnlineMillis: number
    totalAfkMillis: number
    effectiveOnlineMillis: number
    periodStart: number
    periodEnd: number
    /**
     * 各子服的**累计**时长明细，只作证据附注。
     *
     * 与上面三个 millis 字段口径不同：那几个是打卡周期内的窗口值，这份是该玩家在各子服上的全部
     * 历史累计，两者不可相加。name 为空表示该记录没有子服维度（单机服或旧版上报）。
     */
    subServers?: ProjectMinecraftSubServerEvidence[]
  } | null
  reviewStatus: 'APPROVED' | 'REJECTED'
  reviewedByUserId?: string
  reviewedAt?: number | null
  createdAt: number
}

export interface ProjectAcceptanceRecord {
  id: string
  projectId: string
  detailId: string
  operatorUserId: string
  result: string
  fromStatusCode: string
  toStatusCode: string
  reason: string
  createdAt: number
}

export interface ProjectPersonalStats {
  userId: string
  assignedDetails: number
  completedDetails: number
  pendingAcceptanceDetails: number
  acceptedReviews: number
  rejectedReviews: number
  checkIns: number
}

export interface ProjectMemberStats extends ProjectPersonalStats {
  projectId: string
  lastActivityAt: number
}

export interface ProjectProgressEvent {
  id: string
  projectId: string
  detailId: string
  operatorUserId: string
  type: string
  message: string
  metadata: Record<string, unknown>
  createdAt: number
}

export interface ProjectForm {
  name: string
  description: string
  managerUserIds: string[]
  memberUserIds: string[]
  statusesText: string
  defaultStatusCode: string
  doneStatusCode: string
  reworkStatusCode: string
  minCheckInIntervalMinutes: number
  allowedCheckInTypes: string[]
  minecraftPolicy: ProjectMinecraftPolicy
  notificationConnectionId?: string | null
  notificationChannelId: string
  enabled: boolean
}

export interface DetailForm {
  title: string
  description: string
  statusCode: string
  assignmentMode: 'CLAIM' | 'RANDOM'
  candidateScope: 'ALL' | 'SELECTED' | 'PROJECT_MEMBERS'
  requiredAssigneeCount: number
  candidateUserIds: string[]
  assigneeUserIds: string[]
  acceptorUserIds: string[]
  dueAt: string
}

export interface AcceptanceSubmitForm {
  summary: string
}
