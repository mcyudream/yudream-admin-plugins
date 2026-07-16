export interface Option {
  id: string
  name: string
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
  createdAt: number
}

export interface MediaJobTestRequest {
  connectionId: string
  channelId: string
  sourceUrl: string
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
  }
}

export function completeOverride(policy: AutomationPolicy): AutomationPolicyOverride {
  return { ...policy, approvedAnswers: [...policy.approvedAnswers], rejectedAnswers: [...policy.rejectedAnswers] }
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
  }
}
