package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public class AiChatbotAgentService {
    private final PluginAiService ai;

    public AiChatbotAgentService(PluginAiService ai) {
        this.ai = Objects.requireNonNull(ai, "ai");
    }

    public CompletionStage<PluginAiChatResponse> run(AiChatbotGroupPolicy policy, PluginAiChatRequest request) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        return ai.runAgent(policy.agentCode(), request);
    }
}
