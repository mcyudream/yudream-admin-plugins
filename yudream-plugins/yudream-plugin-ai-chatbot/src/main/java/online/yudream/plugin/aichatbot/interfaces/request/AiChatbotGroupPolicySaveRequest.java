package online.yudream.plugin.aichatbot.interfaces.request;

public record AiChatbotGroupPolicySaveRequest(String connectionId, String channelId, boolean enabled, double randomProbability,
                                                int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                                int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart,
                                                String quietHoursEnd, String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                                boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                                String providerCode, String modelCode) { }
