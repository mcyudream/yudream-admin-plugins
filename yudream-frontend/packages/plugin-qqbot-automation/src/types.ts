export interface Option {
  id: string
  name: string
  platform?: string | null
  protocol?: string | null
}

export interface AiModelOption {
  code: string
  name: string
}

export interface AiProviderOption {
  code: string
  name: string
  models: AiModelOption[]
}

export interface AutomationPolicy {
  connectionId: string
  channelId: string
  enabled: boolean
  mediaEnabled: boolean
  mediaProviderEndpoint: string
  joinVerificationEnabled: boolean
  approvedAnswers: string[]
  rejectedAnswers: string[]
  aiFallbackEnabled: boolean
  failClosed: boolean
  providerCode: string
  modelCode: string
  riskMonitorEnabled: boolean
  riskBatchSize: number
  riskAlertConfidence: number
  riskAlertGroup: boolean
  riskAlertAdmin: boolean
  riskAlertAdminUserIds: string[]
  riskMuteEnabled: boolean
  riskMuteLowConfidence: number
  riskMuteLowSeconds: number
  riskMuteHighConfidence: number
  riskMuteHighSeconds: number
}

export interface AutomationPolicyOverride {
  connectionId: string
  channelId: string
  enabled: boolean | null
  mediaEnabled: boolean | null
  mediaProviderEndpoint: string | null
  joinVerificationEnabled: boolean | null
  approvedAnswers: string[] | null
  rejectedAnswers: string[] | null
  aiFallbackEnabled: boolean | null
  failClosed: boolean | null
  providerCode: string | null
  modelCode: string | null
  riskMonitorEnabled: boolean | null
  riskBatchSize: number | null
  riskAlertConfidence: number | null
  riskAlertGroup: boolean | null
  riskAlertAdmin: boolean | null
  riskAlertAdminUserIds: string[] | null
  riskMuteEnabled: boolean | null
  riskMuteLowConfidence: number | null
  riskMuteLowSeconds: number | null
  riskMuteHighConfidence: number | null
  riskMuteHighSeconds: number | null
}

export interface MediaJob {
  id: string
  connectionId: string
  channelId: string
  sourceUrl: string
  trigger?: string
  status: string
  downloadUrl?: string
  error?: string
  commentError?: string
  createdAt: number | string
}

export interface MediaJobTestRequest {
  connectionId: string
  channelId: string
  sourceUrl: string
}

export interface MediaStorageSettings {
  hostDirectory: string | null
  containerDirectory: string
}

export interface UserOption {
  id: string
  username: string
  nickname: string
  deptNames?: string[]
}

export interface PageResult<T> {
  records: T[]
  total: number
}

export const POLICY_OVERRIDE_FIELDS = [
  'enabled',
  'mediaEnabled',
  'mediaProviderEndpoint',
  'joinVerificationEnabled',
  'approvedAnswers',
  'rejectedAnswers',
  'aiFallbackEnabled',
  'failClosed',
  'providerCode',
  'modelCode',
  'riskMonitorEnabled',
  'riskBatchSize',
  'riskAlertConfidence',
  'riskAlertGroup',
  'riskAlertAdmin',
  'riskAlertAdminUserIds',
  'riskMuteEnabled',
  'riskMuteLowConfidence',
  'riskMuteLowSeconds',
  'riskMuteHighConfidence',
  'riskMuteHighSeconds',
] as const

export type PolicyOverrideField = (typeof POLICY_OVERRIDE_FIELDS)[number]

export function emptyPolicy(connectionId = '', channelId = ''): AutomationPolicy {
  return {
    connectionId,
    channelId,
    enabled: true,
    mediaEnabled: false,
    mediaProviderEndpoint: '',
    joinVerificationEnabled: false,
    approvedAnswers: [],
    rejectedAnswers: [],
    aiFallbackEnabled: false,
    failClosed: true,
    providerCode: '',
    modelCode: '',
    riskMonitorEnabled: false,
    riskBatchSize: 20,
    riskAlertConfidence: 80,
    riskAlertGroup: false,
    riskAlertAdmin: false,
    riskAlertAdminUserIds: [],
    riskMuteEnabled: false,
    riskMuteLowConfidence: 70,
    riskMuteLowSeconds: 600,
    riskMuteHighConfidence: 90,
    riskMuteHighSeconds: 86400,
  }
}

export function completeOverride(policy: AutomationPolicy): AutomationPolicyOverride {
  return { ...policy, approvedAnswers: [...policy.approvedAnswers], rejectedAnswers: [...policy.rejectedAnswers], riskAlertAdminUserIds: [...policy.riskAlertAdminUserIds] }
}

export function emptyOverride(connectionId = '', channelId = ''): AutomationPolicyOverride {
  return {
    connectionId,
    channelId,
    enabled: null,
    mediaEnabled: null,
    mediaProviderEndpoint: null,
    joinVerificationEnabled: null,
    approvedAnswers: null,
    rejectedAnswers: null,
    aiFallbackEnabled: null,
    failClosed: null,
    providerCode: null,
    modelCode: null,
    riskMonitorEnabled: null,
    riskBatchSize: null,
    riskAlertConfidence: null,
    riskAlertGroup: null,
    riskAlertAdmin: null,
    riskAlertAdminUserIds: null,
    riskMuteEnabled: null,
    riskMuteLowConfidence: null,
    riskMuteLowSeconds: null,
    riskMuteHighConfidence: null,
    riskMuteHighSeconds: null,
  }
}

export function policyFromOverride(override: AutomationPolicyOverride): AutomationPolicy {
  return {
    connectionId: override.connectionId,
    channelId: override.channelId,
    enabled: override.enabled ?? true,
    mediaEnabled: override.mediaEnabled ?? false,
    mediaProviderEndpoint: override.mediaProviderEndpoint ?? '',
    joinVerificationEnabled: override.joinVerificationEnabled ?? false,
    approvedAnswers: override.approvedAnswers ?? [],
    rejectedAnswers: override.rejectedAnswers ?? [],
    aiFallbackEnabled: override.aiFallbackEnabled ?? false,
    failClosed: override.failClosed ?? true,
    providerCode: override.providerCode ?? '',
    modelCode: override.modelCode ?? '',
    riskMonitorEnabled: override.riskMonitorEnabled ?? false,
    riskBatchSize: override.riskBatchSize ?? 20,
    riskAlertConfidence: override.riskAlertConfidence ?? 80,
    riskAlertGroup: override.riskAlertGroup ?? false,
    riskAlertAdmin: override.riskAlertAdmin ?? false,
    riskAlertAdminUserIds: override.riskAlertAdminUserIds ?? [],
    riskMuteEnabled: override.riskMuteEnabled ?? false,
    riskMuteLowConfidence: override.riskMuteLowConfidence ?? 70,
    riskMuteLowSeconds: override.riskMuteLowSeconds ?? 600,
    riskMuteHighConfidence: override.riskMuteHighConfidence ?? 90,
    riskMuteHighSeconds: override.riskMuteHighSeconds ?? 86400,
  }
}
