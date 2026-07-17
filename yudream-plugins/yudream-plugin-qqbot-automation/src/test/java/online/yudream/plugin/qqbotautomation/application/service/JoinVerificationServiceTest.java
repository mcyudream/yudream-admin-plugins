package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinVerificationServiceTest {

    @Test
    void approvesUsingMilkyGroupRequestEndpointAndTheNativePayload() {
        Invocation invocation = invoke("allow", "request-approve");

        assertEquals("accept_group_request", invocation.method());
        assertEquals("request-approve", invocation.payload().get("request_id"));
        assertEquals("group-a", invocation.payload().get("group_id"));
        assertEquals("user-a", invocation.payload().get("user_id"));
        assertEquals("APPROVE", invocation.audit().get("decision"));
    }

    @Test
    void rejectsUsingMilkyGroupRequestEndpointAndTheNativePayload() {
        Invocation invocation = invoke("deny", "request-reject");

        assertEquals("reject_group_request", invocation.method());
        assertEquals("request-reject", invocation.payload().get("request_id"));
        assertEquals("REJECT", invocation.audit().get("decision"));
    }

    private Invocation invoke(String answer, String requestId) {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy("connection-a", "", true, false, "", true,
                List.of("allow"), List.of("deny"), false, true, "", ""));
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        PluginMessagingRawService raw = (PluginMessagingRawService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingRawService.class}, (proxy, invoked, args) -> {
                    method.set(String.valueOf(args[1]));
                    Map<String, Object> copy = new HashMap<>();
                    ((Map<?, ?>) args[2]).forEach((key, value) -> copy.put(String.valueOf(key), value));
                    payload.set(copy);
                    return CompletableFuture.completedFuture(Map.of());
                });
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "messagingRaw" -> raw;
                    case "documents" -> documents;
                    default -> null;
                });

        new JoinVerificationService(policies, framework).handle(new PluginEvent("", "group_request", "milky", "user-a", "group-a",
                answer, null, null, Map.of("requestId", requestId), "group_request",
                Map.of("request_id", requestId, "group_id", "group-a", "user_id", "user-a", "comment", answer),
                "connection-a", "self-a", requestId));

        return new Invocation(method.get(), payload.get(), documents.findById("join-verification-audit", "connection-a:" + requestId).orElseThrow());
    }

    private record Invocation(String method, Map<String, Object> payload, Map<String, Object> audit) { }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(collection + ":" + id, copy);
            return copy;
        }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(collection + ":" + id)).map(HashMap::new);
        }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return List.of(); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return 0; }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }
    }
}
