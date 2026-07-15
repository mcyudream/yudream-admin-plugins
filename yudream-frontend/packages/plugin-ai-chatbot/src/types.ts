export interface GroupPolicy { connectionId: string; channelId: string; enabled: boolean; randomProbability: number; groupContextLimit: number; personalContextLimit: number; contextExpansionLimit: number; cooldownSeconds: number; hourlyReplyLimit: number; quietHoursStart: string | null; quietHoursEnd: string | null; systemPrompt: string; persona: string; enabledToolNames: string[]; randomToolCallingEnabled: boolean; longTermMemoryEnabled: boolean; semanticMemoryTopK: number; agentCode: string; providerCode?: string; modelCode?: string }
export interface Option { id: string; name: string }
export interface AiTool { name: string; title: string; description: string }
export interface AiAgent { code: string; name: string; description: string }
export interface MemoryFact { key: string; value: string; confidence: number; approved: boolean; updatedAt: number }
export interface MemoryProfile { id: string; connectionId: string; channelId: string; userId: string; platformUserId: string; nickname: string; enabled: boolean; summary: string; tags: string[]; facts: MemoryFact[]; updatedAt: number }
export interface MemoryProfilePage { records: MemoryProfile[]; total: number }
