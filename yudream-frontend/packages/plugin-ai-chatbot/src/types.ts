export interface GroupPolicy { connectionId: string; channelId: string; enabled: boolean; randomProbability: number; groupContextLimit: number; personalContextLimit: number; contextExpansionLimit: number; cooldownSeconds: number; hourlyReplyLimit: number; quietHoursStart: string | null; quietHoursEnd: string | null; systemPrompt: string; persona: string; randomToolCallingEnabled: boolean; longTermMemoryEnabled: boolean; semanticMemoryTopK: number; agentCode: string; providerCode?: string; modelCode?: string; profileProviderCode?: string; profileModelCode?: string; mentionReplyInjection?: string }
export interface Option { id: string; name: string }
export interface AiAgent { code: string; name: string; description: string }
export interface AiModelOption { code: string; name: string }
export interface AiProviderOption { code: string; name: string; models: AiModelOption[] }
export interface MemoryFact { key: string; value: string; confidence?: number; approved?: boolean; updatedAt?: number }
export interface MemoryProfile { id: string; connectionId?: string; channelId?: string; userId?: string; platformUserId?: string; nickname?: string; avatar?: string; enabled?: boolean; summary?: string; personality?: string; interactionStyle?: string; tags?: string[]; facts?: MemoryFact[]; observedMessageCount?: number; replyTriggeredCount?: number; replyCompletedCount?: number; replyFailedCount?: number; lastActivityAt?: number; lastAnalyzedAt?: number; updatedAt?: number }
export interface ProfileObservation { content: string; occurredAt: number }
export interface MemoryProfilePage { records?: MemoryProfile[]; total?: number }
export interface MemoryProfileUpdate { id: string; enabled: boolean; summary: string; tags: string[]; facts: MemoryFact[] }
export interface ActivityFilters { from?: number; to?: number; connectionId?: string; channelId?: string; type?: string; user?: string; bucket?: 'day' | 'hour'; timezone?: string }
export interface ActivityOverview { total: number; success: number; failure: number; users: number; types: Record<string, number> }
export interface ActivityTimelinePoint { bucket: string; total: number; success: number; failure: number }
export interface ActivityHeatmapCell { dayOfWeek: number; hour: number; total: number; success: number }
export interface ActivityUserSummary { userId: string; platformUserId: string; total: number; success: number; failure: number; lastOccurredAt: number }
export interface ActivityEvent { id: string; occurredAt: number; connectionId: string; channelId: string; platformUserId: string; userId: string; type: string; mode: string; success: boolean }
export interface ActivityEventPage { items: ActivityEvent[]; total: number; page: number; size: number }
