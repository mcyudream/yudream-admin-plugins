package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinVerificationServiceTest {

    @Test
    void approvesUsingMilkyGroupRequestEndpointAndTheNativePayload() {
        Invocation invocation = invoke("allow", "101");

        assertEquals("accept_group_request", invocation.method());
        assertEquals(101L, invocation.payload().get("notification_seq"));
        assertEquals("join_request", invocation.payload().get("notification_type"));
        assertEquals("group-a", invocation.payload().get("group_id"));
        assertEquals("user-a", invocation.payload().get("initiator_id"));
        assertEquals("APPROVE", invocation.audit().get("decision"));
    }

    @Test
    void rejectsUsingMilkyGroupRequestEndpointAndTheNativePayload() {
        Invocation invocation = invoke("deny", "102");

        assertEquals("reject_group_request", invocation.method());
        assertEquals(102L, invocation.payload().get("notification_seq"));
        assertEquals("join_request", invocation.payload().get("notification_type"));
        assertEquals("REJECT", invocation.audit().get("decision"));
    }

    @Test
    void approvesOfficialJoinUsingJoinRequestIdAndVerifyInfo() {
        Invocation invocation = invokeOfficial(null, "jr-42", Map.of(
                "group_id", "g-open",
                "user_id", "member-open",
                "join_request_id", "jr-42",
                "verify_info", Map.of("method", "verify_message", "verify_message", "allow")), null);

        assertEquals("set_group_add_request", invocation.method());
        assertEquals("g-open", invocation.payload().get("group_id"));
        assertEquals("member-open", invocation.payload().get("user_id"));
        assertEquals(Boolean.TRUE, invocation.payload().get("approve"));
        assertEquals("approve", invocation.payload().get("op"));
        assertEquals("jr-42", invocation.payload().get("join_request_id"));
        assertEquals("APPROVE", invocation.audit().get("decision"));
    }

    @Test
    void officialAiFallbackApprovesUnmatchedVerifyMessage() {
        Invocation invocation = invokeOfficial(null, "jr-ai", Map.of(
                "group_id", "g-open",
                "user_id", "member-open",
                "join_request_id", "jr-ai",
                "comment", "我想进群玩"), "ALLOW");

        assertEquals("set_group_add_request", invocation.method());
        assertEquals("approve", invocation.payload().get("op"));
        assertEquals("jr-ai", invocation.payload().get("join_request_id"));
        assertEquals("APPROVE", invocation.audit().get("decision"));
    }

    @Test
    void approvesOfficialQaAnswerWhenCommentIncludesQuestion() {
        Invocation invocation = invokeOfficial("物品聚合器的作用：垃圾桶", "jr-qa", Map.of(
                "group_id", "g-open",
                "user_id", "member-open",
                "join_request_id", "jr-qa",
                "comment", "物品聚合器的作用：垃圾桶"), null);

        assertEquals("set_group_add_request", invocation.method());
        assertEquals("approve", invocation.payload().get("op"));
        assertEquals("APPROVE", invocation.audit().get("decision"));
    }

    @Test
    void skipsOfficialRobotAddEventWithoutSendingApproval() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy("connection-official", "", true, false, "", true,
                List.of("allow"), List.of("deny"), false, true, "", ""));
        AtomicReference<String> method = new AtomicReference<>();
        FrameworkServices framework = framework("connection-official", true, method, new AtomicReference<>(), null, documents);
        new JoinVerificationService(policies, framework, new GroupModerationService(framework))
                .handle(new PluginEvent("", "group_request", "milky", "bot-open", "g-open",
                        "", null, null, Map.of("requestId", "g-open"), "group_request",
                        Map.of("native_type", "GROUP_ADD_ROBOT", "group_id", "g-open", "user_id", "bot-open", "request_id", "g-open"),
                        "connection-official", "self-a", "g-open"));
        assertTrue(method.get() == null, "机器人入群事件不应走审批接口");
    }

    private Invocation invoke(String answer, String requestId) {
        return invokeEvent("connection-a", "group-a", "user-a", answer, requestId,
                Map.of("notification_seq", Long.parseLong(requestId), "group_id", "group-a", "initiator_id", "user-a", "comment", answer),
                false, null);
    }

    private Invocation invokeOfficial(String content, String requestId, Map<String, Object> nativeData, String aiReply) {
        return invokeEvent("connection-official", "g-open", "member-open", content, requestId, nativeData, true, aiReply);
    }

    private Invocation invokeEvent(String connectionId, String channelId, String userId, String content, String requestId,
                                   Map<String, Object> nativeData, boolean official, String aiReply) {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy(connectionId, "", true, false, "", true,
                List.of("allow", "垃圾桶"), List.of("deny"), aiReply != null, true, "", ""));
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        FrameworkServices framework = framework(connectionId, official, method, payload, aiReply, documents);

        Map<String, Object> referrer = new HashMap<>();
        if (requestId != null) {
            referrer.put("requestId", requestId);
        }
        new JoinVerificationService(policies, framework, new GroupModerationService(framework))
                .handle(new PluginEvent("", "group_request", "milky", userId, channelId,
                        content, null, null, referrer, official ? "GROUP_JOIN_REQUEST" : "group_join_request",
                        nativeData, connectionId, "self-a", requestId));

        assertTrue(method.get() != null, "应发出入群审批请求");
        return new Invocation(method.get(), payload.get(), documents.findById("join-verification-audit", connectionId + ":" + requestId).orElseThrow());
    }

    private FrameworkServices framework(String connectionId, boolean official, AtomicReference<String> method,
                                        AtomicReference<Map<String, Object>> payload, String aiReply,
                                        InMemoryDocuments documents) {
        PluginMessagingRawService raw = (PluginMessagingRawService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingRawService.class}, (proxy, invoked, args) -> {
                    method.set(String.valueOf(args[1]));
                    Map<String, Object> copy = new HashMap<>();
                    ((Map<?, ?>) args[2]).forEach((key, value) -> copy.put(String.valueOf(key), value));
                    payload.set(copy);
                    return CompletableFuture.completedFuture(Map.of());
                });
        Object messaging = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.messaging.PluginMessagingService")},
                (proxy, invoked, args) -> {
                    if ("connections".equals(invoked.getName())) {
                        return List.of(new PluginMessagingConnection(connectionId, "Bot", official ? "official" : "milky",
                                "self-a", official ? "official" : "milky"));
                    }
                    return null;
                });
        Object ai = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.ai.PluginAiService")},
                (proxy, invoked, args) -> {
                    if ("chat".equals(invoked.getName())) {
                        return CompletableFuture.completedFuture(
                                new online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse(aiReply, List.of()));
                    }
                    return null;
                });
        return (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (proxy, invoked, args) -> {
                    if ("messagingRaw".equals(invoked.getName())) {
                        return raw;
                    }
                    if ("messaging".equals(invoked.getName())) {
                        return messaging;
                    }
                    if ("documents".equals(invoked.getName())) {
                        return documents;
                    }
                    if ("ai".equals(invoked.getName())) {
                        return ai;
                    }
                    return null;
                });
    }

    private Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
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
