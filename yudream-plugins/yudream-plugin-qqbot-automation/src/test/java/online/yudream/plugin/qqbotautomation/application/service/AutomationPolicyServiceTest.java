package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicyOverride;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationPolicyServiceTest {

    @Test
    void resolvesPartialGroupOverrideAgainstConnectionDefaults() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService service = new AutomationPolicyService(documents);
        service.saveDefaults(defaults("connection-a"));
        service.saveOverride(new AutomationPolicyOverride("connection-a", "group-a", null, false, null, null,
                null, null, null, null, null, null));

        AutomationPolicy policy = service.resolve("connection-a", "group-a");

        assertTrue(policy.enabled());
        assertFalse(policy.mediaEnabled());
        assertEquals("http://localhost:8080/parser", policy.mediaProviderEndpoint());
        assertTrue(policy.joinVerificationEnabled());
        assertEquals(List.of("allow"), policy.approvedAnswers());
        assertEquals("provider-a", policy.providerCode());
        assertEquals("model-a", policy.modelCode());
    }

    @Test
    void groupEnabledFalseOverridesOnlyThatGroup() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService service = new AutomationPolicyService(documents);
        service.saveDefaults(defaults("connection-a"));
        service.saveOverride(new AutomationPolicyOverride("connection-a", "disabled-group", false, null, null, null,
                null, null, null, null, null, null));

        assertFalse(service.resolve("connection-a", "disabled-group").enabled());
        assertTrue(service.resolve("connection-a", "other-group").enabled());
    }

    @Test
    void deletingGroupOverrideRestoresConnectionInheritance() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService service = new AutomationPolicyService(documents);
        service.saveDefaults(defaults("connection-a"));
        service.saveOverride(new AutomationPolicyOverride("connection-a", "group-a", null, false, null, null,
                null, null, null, null, null, null));

        service.deleteOverride("connection-a", "group-a");

        AutomationPolicy restored = service.resolve("connection-a", "group-a");
        assertTrue(restored.mediaEnabled());
        assertEquals("http://localhost:8080/parser", restored.mediaProviderEndpoint());
        assertTrue(service.getOverride("connection-a", "group-a").isEmpty());
    }

    @Test
    void readsLegacyCompleteGroupPolicyAsAnOverride() {
        InMemoryDocuments documents = new InMemoryDocuments();
        documents.save("automation-policy", "connection-a:legacy-group", Map.of(
                "connectionId", "connection-a",
                "channelId", "legacy-group",
                "enabled", false,
                "mediaEnabled", true,
                "mediaProviderEndpoint", "https://media.example.test/parse",
                "approvedAnswers", List.of("okay"),
                "providerCode", "legacy-provider",
                "modelCode", "legacy-model"
        ));
        AutomationPolicyService service = new AutomationPolicyService(documents);

        AutomationPolicy policy = service.resolve("connection-a", "legacy-group");

        assertFalse(policy.enabled());
        assertTrue(policy.mediaEnabled());
        assertEquals("https://media.example.test/parse", policy.mediaProviderEndpoint());
        assertEquals(List.of("okay"), policy.approvedAnswers());
        assertEquals("legacy-provider", policy.providerCode());
        assertEquals("legacy-model", policy.modelCode());
        service.migrateLegacyPolicies();
        assertEquals(1, service.countOverrides("connection-a"));
        assertEquals(1, service.pageOverrides("connection-a", 1, 10).size());
    }

    @Test
    void permitsLocalDockerFallbackWhenMediaEndpointIsBlank() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService service = new AutomationPolicyService(documents);

        AutomationPolicy saved = service.saveDefaults(new AutomationPolicy("connection-a", "", true, true, "", false,
                List.of(), List.of(), false, true, "", ""));

        assertEquals("", saved.mediaProviderEndpoint());
    }

    private AutomationPolicy defaults(String connectionId) {
        return new AutomationPolicy(connectionId, "", true, true, "http://localhost:8080/parser", true,
                List.of("allow"), List.of("deny"), true, false, "provider-a", "model-a");
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(key(collection, id), copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(key(collection, id))).map(HashMap::new);
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> matching = values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + ":"))
                    .<Map<String, Object>>map(entry -> new HashMap<>(entry.getValue()))
                    .toList();
            int from = Math.min(Math.max(page - 1, 0) * size, matching.size());
            return new ArrayList<>(matching.subList(from, Math.min(from + size, matching.size())));
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matching = values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + ":"))
                    .map(Map.Entry::getValue)
                    .filter(document -> java.util.Objects.equals(document.get(field), value))
                    .<Map<String, Object>>map(document -> new HashMap<>(document))
                    .toList();
            int from = Math.min(Math.max(page - 1, 0) * size, matching.size());
            return new ArrayList<>(matching.subList(from, Math.min(from + size, matching.size())));
        }

        @Override
        public long count(String collection) {
            return values.keySet().stream().filter(key -> key.startsWith(collection + ":")).count();
        }

        @Override
        public void delete(String collection, String id) {
            values.remove(key(collection, id));
        }

        private String key(String collection, String id) {
            return collection + ":" + id;
        }
    }
}
