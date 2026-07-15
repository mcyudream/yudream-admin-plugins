export interface Option { id: string; name: string }
export interface AutomationPolicy { connectionId: string; channelId: string; enabled: boolean; mediaEnabled: boolean; mediaProviderEndpoint: string; joinVerificationEnabled: boolean; approvedAnswers: string[]; rejectedAnswers: string[]; aiFallbackEnabled: boolean; failClosed: boolean; providerCode: string; modelCode: string }
export interface MediaJob { id: string; connectionId: string; channelId: string; sourceUrl: string; status: string; downloadUrl?: string; error?: string; createdAt: number }
export interface PageResult<T> { records: T[]; total: number }
