export interface GroupPolicy { connectionId: string; channelId: string; enabled: boolean; randomProbability: number; groupContextLimit: number; personalContextLimit: number; contextExpansionLimit: number; cooldownSeconds: number; hourlyReplyLimit: number; quietHoursStart: string | null; quietHoursEnd: string | null; systemPrompt: string; persona: string; enabledToolNames: string[]; providerCode: string; modelCode: string }
export interface Option { id: string; name: string }
export interface AiTool { name: string; title: string; description: string }
export interface AiProvider { code: string; name: string; models: { code: string; name: string }[] }
