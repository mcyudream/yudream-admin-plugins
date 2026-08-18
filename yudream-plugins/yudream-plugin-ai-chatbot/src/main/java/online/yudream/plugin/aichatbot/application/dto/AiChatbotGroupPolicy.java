package online.yudream.plugin.aichatbot.application.dto;

public record AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                    int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                    int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                    String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                    boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                    String agentCode, String providerCode, String modelCode,
                                    String mentionReplyInjection,
                                    String wikiSpaceSlug, boolean wikiHelpEnabled, int wikiImageLimit) {
    public static final String BUILTIN_AGENT_CODE = "builtin-group-chatbot";
    public static final int DEFAULT_WIKI_IMAGE_LIMIT = 3;
    public static final int MAX_WIKI_IMAGE_LIMIT = 5;

    public AiChatbotGroupPolicy {
        agentCode = agentCode == null || agentCode.isBlank() ? BUILTIN_AGENT_CODE : agentCode.trim();
        providerCode = providerCode == null ? "" : providerCode.trim();
        modelCode = modelCode == null ? "" : modelCode.trim();
        persona = persona == null ? "" : persona;
        enabledToolNames = enabledToolNames == null ? java.util.List.of() : java.util.List.copyOf(enabledToolNames);
        mentionReplyInjection = mentionReplyInjection == null ? "" : mentionReplyInjection.trim();
        wikiSpaceSlug = wikiSpaceSlug == null ? "" : wikiSpaceSlug.trim();
        wikiImageLimit = wikiImageLimit < 1 ? DEFAULT_WIKI_IMAGE_LIMIT : wikiImageLimit;
    }

    public AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                String agentCode, String providerCode, String modelCode, String mentionReplyInjection) {
        this(connectionId, channelId, enabled, randomProbability, groupContextLimit, personalContextLimit,
                contextExpansionLimit, cooldownSeconds, hourlyReplyLimit, quietHoursStart, quietHoursEnd, systemPrompt,
                persona, enabledToolNames, randomToolCallingEnabled, longTermMemoryEnabled, semanticMemoryTopK,
                agentCode, providerCode, modelCode, mentionReplyInjection, "", false, DEFAULT_WIKI_IMAGE_LIMIT);
    }

    public AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                String agentCode, String providerCode, String modelCode) {
        this(connectionId, channelId, enabled, randomProbability, groupContextLimit, personalContextLimit,
                contextExpansionLimit, cooldownSeconds, hourlyReplyLimit, quietHoursStart, quietHoursEnd, systemPrompt,
                persona, enabledToolNames, randomToolCallingEnabled, longTermMemoryEnabled, semanticMemoryTopK,
                agentCode, providerCode, modelCode, "");
    }

    public AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                String providerCode, String modelCode) {
        this(connectionId, channelId, enabled, randomProbability, groupContextLimit, personalContextLimit,
                contextExpansionLimit, cooldownSeconds, hourlyReplyLimit, quietHoursStart, quietHoursEnd, systemPrompt,
                persona, enabledToolNames, randomToolCallingEnabled, longTermMemoryEnabled, semanticMemoryTopK,
                BUILTIN_AGENT_CODE, providerCode, modelCode, "");
    }

    public AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                 int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                 int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                 String systemPrompt, String persona, java.util.List<String> enabledToolNames,
                                 String providerCode, String modelCode) {
        this(connectionId, channelId, enabled, randomProbability, groupContextLimit, personalContextLimit,
                contextExpansionLimit, cooldownSeconds, hourlyReplyLimit, quietHoursStart, quietHoursEnd, systemPrompt,
                persona, enabledToolNames, false, false, 5, BUILTIN_AGENT_CODE, providerCode, modelCode, "");
    }

    public boolean toolCallingEnabled(String trigger) {
        return !enabledToolNames.isEmpty() && ("MENTION".equals(trigger)
                || ("RANDOM".equals(trigger) && randomToolCallingEnabled));
    }

    /** 求助检索分支：启用即生效；知识库标识留空时检索全部开放公开阅读的知识库 */
    public boolean wikiHelpAvailable() {
        return wikiHelpEnabled;
    }

    public static AiChatbotGroupPolicy defaults(String connectionId, String channelId) {
        return new AiChatbotGroupPolicy(connectionId, channelId, true, 0.03d, 12, 16, 12, 30, 30,
                null, null, "你是 YuDream 群聊助手，回答简短、友好、准确。", "", java.util.List.of(),
                false, false, 5, BUILTIN_AGENT_CODE, "", "", "");
    }
}
