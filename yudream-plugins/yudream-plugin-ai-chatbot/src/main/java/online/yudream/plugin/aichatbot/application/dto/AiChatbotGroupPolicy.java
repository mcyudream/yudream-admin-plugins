package online.yudream.plugin.aichatbot.application.dto;

public record AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                   int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                   int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                   String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                   boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                   String providerCode, String modelCode) {
    public AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                 int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                 int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                 String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                 String providerCode, String modelCode) {
        this(connectionId, channelId, enabled, randomProbability, groupContextLimit, personalContextLimit,
                contextExpansionLimit, cooldownSeconds, hourlyReplyLimit, quietHoursStart, quietHoursEnd, systemPrompt,
                persona, enabledToolNames, false, false, 5, providerCode, modelCode);
    }

    public boolean toolCallingEnabled(String trigger) {
        return !enabledToolNames.isEmpty() && ("MENTION".equals(trigger)
                || ("RANDOM".equals(trigger) && randomToolCallingEnabled));
    }

    public static AiChatbotGroupPolicy defaults(String connectionId, String channelId) {
        return new AiChatbotGroupPolicy(connectionId, channelId, true, 0.03d, 12, 16, 12, 30, 30,
                null, null, "你是 YuDream 群聊助手，回答简短、友好、准确。", "", java.util.List.of(), "", "");
    }
}
