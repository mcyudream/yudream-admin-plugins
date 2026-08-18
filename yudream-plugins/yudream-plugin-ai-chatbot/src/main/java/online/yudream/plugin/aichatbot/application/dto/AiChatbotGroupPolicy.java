package online.yudream.plugin.aichatbot.application.dto;

/**
 * 群聊策略。工具调用（含 Wiki 求助检索）不再由群策略配置，统一由宿主 Agent 应用的工作流编排；
 * 本记录只保留群行为（触发、限流、记忆、人设）与运行参数（agent/模型）。
 */
public record AiChatbotGroupPolicy(String connectionId, String channelId, boolean enabled, double randomProbability,
                                    int groupContextLimit, int personalContextLimit, int contextExpansionLimit,
                                    int cooldownSeconds, int hourlyReplyLimit, String quietHoursStart, String quietHoursEnd,
                                    String systemPrompt, String persona,
                                    boolean randomToolCallingEnabled, boolean longTermMemoryEnabled, int semanticMemoryTopK,
                                    String agentCode, String providerCode, String modelCode,
                                    String profileProviderCode, String profileModelCode,
                                    String mentionReplyInjection) {
    public static final String BUILTIN_AGENT_CODE = "builtin-group-chatbot";

    public AiChatbotGroupPolicy {
        agentCode = agentCode == null || agentCode.isBlank() ? BUILTIN_AGENT_CODE : agentCode.trim();
        providerCode = providerCode == null ? "" : providerCode.trim();
        modelCode = modelCode == null ? "" : modelCode.trim();
        profileProviderCode = profileProviderCode == null ? "" : profileProviderCode.trim();
        profileModelCode = profileModelCode == null ? "" : profileModelCode.trim();
        persona = persona == null ? "" : persona;
        mentionReplyInjection = mentionReplyInjection == null ? "" : mentionReplyInjection.trim();
    }

    /** 记忆画像分析模型：未单独配置时跟随对话模型，对话模型也未配时由宿主默认模型处理 */
    public String effectiveProfileProviderCode() {
        return profileProviderCode.isBlank() ? providerCode : profileProviderCode;
    }

    public String effectiveProfileModelCode() {
        return profileModelCode.isBlank() ? modelCode : profileModelCode;
    }

    /** @ 回复始终允许工具调用；随机回复仅在显式开启时允许（工具范围由宿主按触发方式与权限码过滤） */
    public boolean toolCallingEnabled(String trigger) {
        return "MENTION".equals(trigger) || ("RANDOM".equals(trigger) && randomToolCallingEnabled);
    }

    public static AiChatbotGroupPolicy defaults(String connectionId, String channelId) {
        return new AiChatbotGroupPolicy(connectionId, channelId, true, 0.03d, 12, 16, 12, 30, 30,
                null, null, "你是 YuDream 群聊助手，回答简短、友好、准确。", "",
                false, false, 5, BUILTIN_AGENT_CODE, "", "", "", "", "");
    }
}
