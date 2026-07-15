package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.ai.PluginAiAgentOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatbotAgentServiceTest {

    @Test
    void runsSelectedAgentInsteadOfDirectModelChat() {
        RecordingAiService ai = new RecordingAiService();
        AiChatbotAgentService service = new AiChatbotAgentService(ai);
        PluginAiChatRequest request = new PluginAiChatRequest("runtime", "hello", null, null, List.of(), null, true);

        PluginAiChatResponse response = service.run(AiChatbotGroupPolicy.defaults("milky", "10001"), request)
                .toCompletableFuture().join();

        assertEquals(AiChatbotGroupPolicy.BUILTIN_AGENT_CODE, ai.agentCode);
        assertEquals("agent reply", response.content());
        assertEquals(0, ai.directChatCalls);
    }

    private static final class RecordingAiService implements PluginAiService {
        private String agentCode;
        private int directChatCalls;

        @Override
        public CompletionStage<PluginAiChatResponse> chat(PluginAiChatRequest request) {
            directChatCalls++;
            return CompletableFuture.failedFuture(new AssertionError("direct chat must not be used"));
        }

        @Override public List<PluginAiToolDescriptor> tools() { return List.of(); }
        @Override public List<PluginAiProviderOption> providers() { return List.of(); }
        @Override public List<PluginAiAgentOption> agents() { return List.of(); }

        @Override
        public CompletionStage<PluginAiChatResponse> runAgent(String agentCode, PluginAiChatRequest request) {
            this.agentCode = agentCode;
            return CompletableFuture.completedFuture(new PluginAiChatResponse("agent reply", List.of()));
        }
    }
}
