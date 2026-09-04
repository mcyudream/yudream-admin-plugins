package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.questionbank.infrastructure.FakeFramework;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiModelOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI 题目解析与供应商选项：输出容错、空/超长输入、AI 不可用降级。 */
class AiImportServiceTest {
    private FakeFramework framework;
    private AiImportService service;

    @BeforeEach
    void setUp() {
        framework = new FakeFramework();
        service = new AiImportService(new SettingsService(new FakeDocumentStore()), framework,
                new JsonSupport(new ObjectMapper()));
    }

    @Test
    void parseToleratesCodeFenceAndChineseTypeNames() {
        framework.setAi(new StubAi("""
                ```json
                [
                  {"type":"单选题","content":"1+1=?","options":["1","2","3"],"answer":"B","difficulty":"2","tags":["算术"]},
                  {"type":"SHORT","content":"简述光合作用","referenceAnswer":"利用光能合成有机物"},
                  {"type":"未知题型","content":"无效题"},
                  {"type":"FILL","content":"水的化学式是__","blanks":[["H2O","H₂O"]]}
                ]
                ```
                """));
        List<QuestionPayload> drafts = service.parse("一些复制来的文本");
        assertEquals(3, drafts.size());
        assertEquals("SINGLE", drafts.get(0).type());
        assertEquals(List.of("1", "2", "3"), drafts.get(0).options());
        assertEquals(2, drafts.get(0).difficulty());
        assertEquals(List.of("算术"), drafts.get(0).tags());
        assertEquals("SHORT", drafts.get(1).type());
        assertEquals("FILL", drafts.get(2).type());
        assertEquals(List.of(List.of("H2O", "H₂O")), drafts.get(2).blanks());
    }

    @Test
    void parseRejectsBlankOversizeAndUnusableOutput() {
        assertThrows(IllegalArgumentException.class, () -> service.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> service.parse("x".repeat(20001)));
        framework.setAi(new StubAi("这不是 JSON"));
        assertThrows(IllegalStateException.class, () -> service.parse("题目内容"));
        framework.setAi(new StubAi("[{\"type\":\"SINGLE\"}]"));
        assertThrows(IllegalStateException.class, () -> service.parse("题目内容"));
    }

    @Test
    void aiUnavailableFailsClearlyAndOptionsDegrade() {
        // FakeFramework 默认无 AI
        assertThrows(IllegalStateException.class, () -> service.parse("题目内容"));
        assertTrue(service.providerOptions().isEmpty());

        framework.setAi(new StubAi("[]", List.of(
                new PluginAiProviderOption("openai", "OpenAI", List.of(new PluginAiModelOption("gpt-x", "GPT-X"))))));
        List<AiImportService.AiProviderOptionView> options = service.providerOptions();
        assertEquals(1, options.size());
        assertEquals("openai", options.get(0).code());
        assertEquals("gpt-x", options.get(0).models().get(0).code());
        assertNull(new SettingsService(new FakeDocumentStore()).aiProviderCode());
    }

    /** 固定输出的 AI 假实现。 */
    private static final class StubAi implements online.yudream.base.plugin.spi.system.ai.PluginAiService {
        private final String output;
        private final List<PluginAiProviderOption> providers;

        StubAi(String output) {
            this(output, List.of());
        }

        StubAi(String output, List<PluginAiProviderOption> providers) {
            this.output = output;
            this.providers = providers;
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> chat(PluginAiChatRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(new PluginAiChatResponse(output, List.of()));
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor> tools() {
            return List.of();
        }

        @Override
        public List<PluginAiProviderOption> providers() {
            return providers;
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiAgentOption> agents() {
            return List.of();
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> runAgent(String agentCode, PluginAiChatRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
