export interface ActivityProofStatus {
  dependencies: ActivityProofDependencies
  settings: ActivityProofSettings
}

export interface ActivityProofDependencies {
  minecraftReady: boolean
  studentInfoReady: boolean
  wordTemplateReady: boolean
  formReady: boolean
}

export interface ActivityProofSettings {
  templateReady: boolean
  templateId: string
  templateCode: string
  templateName: string
  templateFilename: string
  templateUpdatedAt: TimeValue
  defaultActivityName: string
  defaultCollege: string
  defaultIssuer: string
  qqNotifyEnabled: boolean
  qqConnectionId: string
  qqGroupIds: string[]
  qqMessageTemplate: string
  updatedAt: TimeValue
}

export interface ActivityUserOption {
  id: string
  username: string
  nickname: string
  deptNames: string[]
}

export interface ActivityUserPickerRow extends ActivityUserOption {
  label: string
}

export interface ActivityTemplateMembers {
  templateId: string
  members: ActivityUserOption[]
  updatedAt: TimeValue
  updatedBy: string
}

export interface ActivityQqConnection {
  id: string
  name: string
  platform: string
}

export interface ActivityQqGroup {
  id: string
  name: string
}

export interface ActivityProofTemplate {
  id: string
  code: string
  name: string
  originalFilename: string
  updatedAt: TimeValue
}

export interface ActivityProofServer {
  id: string
  name: string
  enabled: boolean
  currentSeasonName: string
  currentSeasonStartedAt: TimeValue
}

export interface ActivityProofMapping {
  id: string
  serverId: string
  playerId: string
  playerName: string
  studentNo: string
  createdAt: TimeValue
  updatedAt: TimeValue
}

export type ActivityStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'

export type ActivityBindingType = 'PLAYTIME' | 'FORM'

export interface ActivityBinding {
  type: ActivityBindingType
  serverId: string
  serverName: string
  minOnlineMinutes: number
  includeAfk: boolean
  formCode: string
  formName: string
  requirementText: string
}

export interface Activity {
  id: string
  title: string
  summary: string
  description: string
  coverUrl: string
  signupStart: TimeValue
  signupEnd: TimeValue
  activityStart: TimeValue
  activityEnd: TimeValue
  status: ActivityStatus
  deptMode: string
  allowedDeptIds: string[]
  allowedDeptNames: string[]
  bindings: ActivityBinding[]
  participantCount: number
  verifiedCount: number
  createdBy: string
  createdAt: TimeValue
  updatedAt: TimeValue
  publishedAt: TimeValue
}

export interface ActivityDeptOption {
  id: string
  name: string
  label: string
  parentId: string
}

export interface ActivityFormOption {
  code: string
  name: string
  description: string
  publishedAt: TimeValue
}

export interface ActivityBindingForm {
  type: ActivityBindingType
  serverId: string
  minOnlineMinutes: number
  includeAfk: boolean
  formCode: string
}

export interface ActivitySaveForm {
  id: string
  title: string
  summary: string
  description: string
  coverUrl: string
  signupStartText: string
  signupEndText: string
  activityStartText: string
  activityEndText: string
  deptMode: string
  allowedDeptIds: string[]
  bindings: ActivityBindingForm[]
}

export interface UserActivity {
  id: string
  title: string
  summary: string
  description: string
  coverUrl: string
  signupStart: TimeValue
  signupEnd: TimeValue
  activityStart: TimeValue
  activityEnd: TimeValue
  status: ActivityStatus
  deptRestricted: boolean
  allowedDeptNames: string[]
  requirements: string[]
  participantCount: number
  eligible: boolean
  joinDisabledReason: string
  participationStatus: string
  joinedAt: TimeValue
  verifyStatus: string
  verifyNote: string
}

export interface ActivityParticipantAdmin {
  activityId: string
  userId: string
  username: string
  studentName: string
  studentNo: string
  className: string
  college: string
  playerId: string
  playerName: string
  status: string
  joinedAt: TimeValue
  cancelledAt: TimeValue
  verifyStatus: string
  verifiedAt: TimeValue
  verifyNote: string
}

export interface MyParticipation {
  activityId: string
  title: string
  coverUrl: string
  activityStart: TimeValue
  activityEnd: TimeValue
  activityStatus: ActivityStatus
  status: string
  joinedAt: TimeValue
  cancelledAt: TimeValue
  verifyStatus: string
  verifiedAt: TimeValue
  verifyNote: string
}

export interface ActivityVerifyResult {
  activityId: string
  total: number
  passed: number
  failed: number
}

export interface ActivityProofExportRecord {
  id: string
  activityId: string
  serverId: string
  serverName: string
  activityName: string
  outputFilename: string
  downloadPath: string
  participantCount: number
  unmatchedCount: number
  operatorUserId: string
  generatedAt: TimeValue
  stampedPdfReady: boolean
  stampedPdfFilename: string
  stampedPdfDownloadPath: string
  stampedPdfSize: number
  stampedPdfUploadedAt: TimeValue
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export type TimeValue = number | string | number[] | null | undefined
