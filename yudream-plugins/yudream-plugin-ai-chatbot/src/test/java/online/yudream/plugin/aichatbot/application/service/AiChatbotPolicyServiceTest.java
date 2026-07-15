package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiChatbotPolicyServiceTest {

    @Test
    void migratesLegacyModelPolicyToBuiltinAgentAndKeepsEmbeddingConfiguration() {
        InMemoryDocuments documents = new InMemoryDocuments();
        documents.save("group-policy", "milky:10001", Map.of(
                "connectionId", "milky",
                "channelId", "10001",
                "providerCode", "embedding-provider",
                "modelCode", "text-embedding-3-small"
        ));

        AiChatbotPolicyService service = new AiChatbotPolicyService(documents);
        AiChatbotGroupPolicy policy = service.get("milky", "10001");

        assertEquals(AiChatbotGroupPolicy.BUILTIN_AGENT_CODE, policy.agentCode());
        assertEquals("embedding-provider", policy.providerCode());
        assertEquals("text-embedding-3-small", policy.modelCode());
        service.save(policy);
        assertEquals(AiChatbotGroupPolicy.BUILTIN_AGENT_CODE,
                documents.findById("group-policy", "milky:10001").orElseThrow().get("agentCode"));
    }

    @Test
    void newDefaultPolicyPersistsOnlyAgentSelectionForChatRuntime() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotPolicyService service = new AiChatbotPolicyService(documents);

        service.save(AiChatbotGroupPolicy.defaults("milky", "10002"));

        Map<String, Object> saved = documents.findById("group-policy", "milky:10002").orElseThrow();
        assertEquals(AiChatbotGroupPolicy.BUILTIN_AGENT_CODE, saved.get("agentCode"));
        assertFalse(saved.containsKey("providerCode"));
        assertFalse(saved.containsKey("modelCode"));
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(collection + ":" + id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(collection + ":" + id));
        }

        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return List.of(); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return 0; }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }
    }
}
