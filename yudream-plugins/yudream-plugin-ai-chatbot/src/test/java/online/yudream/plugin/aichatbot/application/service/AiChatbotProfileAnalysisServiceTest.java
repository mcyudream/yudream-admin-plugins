package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.ai.PluginAiAgentOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfile;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotMemoryProfileUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotProfileAnalysisServiceTest {

    @Test
    void analyzeMergesParsedAiResultAndProtectsApprovedFacts() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService profiles = new AiChatbotMemoryProfileService(documents);
        profiles.observe("milky", "100", "1", "qq-1", "Alice", "", "我喜欢 Java 和机械键盘");
        profiles.update(new AiChatbotMemoryProfileUpdateRequest("milky:100:1", true, "", List.of(), List.of(new AiChatbotMemoryFact("identity", "群管理员", 1d, true, 0))));
        StubAiService ai = new StubAiService("```json\n{\"summary\":\"技术爱好者\",\"personality\":\"理性内敛\",\"interactionStyle\":\"简短直接\",\"tags\":[\"技术\",\"外设\"],"
                + "\"facts\":[{\"key\":\"interest\",\"value\":\"喜欢 Java\",\"confidence\":1.7},{\"key\":\"identity\",\"value\":\"AI 伪造的身份\"},{\"key\":\"unknown\",\"value\":\"归为 note\"}]}\n```");
        AiChatbotProfileAnalysisService service = new AiChatbotProfileAnalysisService(profiles, new AiChatbotPolicyService(documents), ai);

        AiChatbotMemoryProfile analyzed = service.analyze("milky:100:1");

        assertEquals("技术爱好者", analyzed.summary());
        assertEquals("理性内敛", analyzed.personality());
        assertEquals("简短直接", analyzed.interactionStyle());
        assertEquals(List.of("技术", "外设"), analyzed.tags());
        assertTrue(analyzed.lastAnalyzedAt() > 0);
        assertEquals(1, analyzed.facts().stream().filter(fact -> fact.key().equals("identity")).count());
        assertEquals("群管理员", analyzed.facts().stream().filter(fact -> fact.key().equals("identity")).findFirst().orElseThrow().value());
        AiChatbotMemoryFact interest = analyzed.facts().stream().filter(fact -> fact.key().equals("interest")).findFirst().orElseThrow();
        assertEquals("喜欢 Java", interest.value());
        assertEquals(1d, interest.confidence());
        assertFalse(interest.approved());
        assertTrue(analyzed.facts().stream().anyMatch(fact -> fact.key().equals("note") && fact.value().equals("归为 note")));
        assertFalse(ai.lastRequest.toolCallingEnabled());
        assertTrue(ai.lastRequest.userPrompt().contains("我喜欢 Java 和机械键盘"));
    }

    @Test
    void analyzeRejectsProfileWithoutObservations() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService profiles = new AiChatbotMemoryProfileService(documents);
        profiles.recordReply("milky", "100", "1", "qq-1", "Alice", "", "COMPLETED");
        AiChatbotProfileAnalysisService service = new AiChatbotProfileAnalysisService(profiles, new AiChatbotPolicyService(documents), new StubAiService("{}"));
        assertThrows(IllegalArgumentException.class, () -> service.analyze("milky:100:1"));
    }

    @Test
    void analyzeRejectsNonJsonAiOutputAndLeavesProfileUntouched() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService profiles = new AiChatbotMemoryProfileService(documents);
        profiles.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        AiChatbotProfileAnalysisService service = new AiChatbotProfileAnalysisService(profiles, new AiChatbotPolicyService(documents), new StubAiService("我现在无法分析该用户。"));

        assertThrows(IllegalStateException.class, () -> service.analyze("milky:100:1"));
        assertEquals("", profiles.get("milky:100:1").summary());
        assertEquals(0, profiles.get("milky:100:1").lastAnalyzedAt());
    }

    @Test
    void analyzeWrapsAiFailure() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService profiles = new AiChatbotMemoryProfileService(documents);
        profiles.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        StubAiService ai = new StubAiService(null);
        AiChatbotProfileAnalysisService service = new AiChatbotProfileAnalysisService(profiles, new AiChatbotPolicyService(documents), ai);
        assertThrows(IllegalStateException.class, () -> service.analyze("milky:100:1"));
    }

    private static final class StubAiService implements PluginAiService {
        private final String content;
        private PluginAiChatRequest lastRequest;
        private StubAiService(String content) { this.content = content; }
        @Override public CompletionStage<PluginAiChatResponse> chat(PluginAiChatRequest request) { lastRequest = request; return content == null ? CompletableFuture.failedFuture(new RuntimeException("provider down")) : CompletableFuture.completedFuture(new PluginAiChatResponse(content, List.of())); }
        @Override public List<PluginAiToolDescriptor> tools() { return List.of(); }
        @Override public List<PluginAiProviderOption> providers() { return List.of(); }
        @Override public List<PluginAiAgentOption> agents() { return List.of(); }
        @Override public CompletionStage<PluginAiChatResponse> runAgent(String agentCode, PluginAiChatRequest request) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();
        @Override public Map<String, Object> save(String collection, String id, Map<String, Object> document) { Map<String, Object> copy = new HashMap<>(document); values.put(collection + ":" + id, copy); return copy; }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.ofNullable(values.get(collection + ":" + id)); }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return List.of(); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return values.keySet().stream().filter(key -> key.startsWith(collection + ":")).count(); }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }
    }
}
