package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskMonitorServiceTest {

    @Test
    void inspectsAfterBatchSizeAndMutesHighConfidenceSender() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy("connection-a", "", true, false, "", false,
                List.of(), List.of(), false, true, "", "",
                true, 5, 60, true, true, List.of("1001"),
                true, 50, 600, 90, 86400, "alert-group"));
        AtomicReference<String> aiPrompt = new AtomicReference<>();
        AtomicReference<Map<String, Object>> mutePayload = new AtomicReference<>();
        AtomicInteger rawCalls = new AtomicInteger();
        List<PluginMessageRequest> groupMessages = new ArrayList<>();
        List<String> dmUserIds = new ArrayList<>();
        FrameworkServices framework = framework(aiPrompt, mutePayload, rawCalls, groupMessages, dmUserIds);
        RiskMonitorService monitor = new RiskMonitorService(policies, documents, framework, new GroupModerationService(framework));

        monitor.handle(event("12345", "hello"));
        monitor.handle(event("12345", "刷单兼职加我微信"));
        assertEquals(0, rawCalls.get(), "未达到送检条数时不应送检");
        monitor.handle(event("67890", "今天天气不错"));
        monitor.handle(event("67890", "吃了吗"));
        monitor.handle(event("12345", "在吗"));

        assertTrue(aiPrompt.get().contains("刷单兼职加我微信"), "送检内容应包含全部消息与用户上下文");
        assertTrue(aiPrompt.get().contains("12345"), "送检内容应包含发送者 QQ");
        assertEquals("set_group_member_mute", mutePayload.get().get("method"));
        assertEquals(12345L, mutePayload.get().get("user_id"));
        assertEquals(86400L, mutePayload.get().get("duration"));
        assertEquals(1, groupMessages.size(), "指定告警群应收到风险告警");
        assertEquals("alert-group", groupMessages.getFirst().channelId());
        assertTrue(String.valueOf(groupMessages.getFirst().content().content()).contains("来源群：777"));
        assertTrue(String.valueOf(groupMessages.getFirst().content().content()).contains("置信度 95"));
        assertEquals(List.of("1001"), dmUserIds, "管理员应收到定向告警");
        assertEquals(1, documents.values("risk-check-log").size(), "应落审计记录");
    }

    @Test
    void cleanBatchProducesNoActions() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy("connection-a", "", true, false, "", false,
                List.of(), List.of(), false, true, "", "",
                true, 5, 60, true, true, List.of("1001"),
                true, 50, 600, 90, 86400, "alert-group"));
        AtomicReference<String> aiPrompt = new AtomicReference<>();
        AtomicInteger rawCalls = new AtomicInteger();
        List<PluginMessageRequest> groupMessages = new ArrayList<>();
        List<String> dmUserIds = new ArrayList<>();
        FrameworkServices framework = frameworkWithAi(aiPrompt, "[]", (method, payload) -> rawCalls.incrementAndGet(), groupMessages, dmUserIds);
        RiskMonitorService monitor = new RiskMonitorService(policies, documents, framework, new GroupModerationService(framework));

        for (int i = 0; i < 5; i++) {
            monitor.handle(event("12345", "hello " + i));
        }

        assertEquals(0, rawCalls.get());
        assertTrue(groupMessages.isEmpty());
        assertTrue(dmUserIds.isEmpty());
        assertEquals(1, documents.values("risk-check-log").size());
    }

    private PluginEvent event(String userId, String content) {
        return new PluginEvent("", "message_receive", "milky", userId, "777",
                content, null, null, Map.of(), "message_receive", Map.of(), "connection-a", "self-a", null);
    }

    private FrameworkServices framework(AtomicReference<String> aiPrompt, AtomicReference<Map<String, Object>> mutePayload,
                                        AtomicInteger rawCalls, List<PluginMessageRequest> groupMessages, List<String> dmUserIds) {
        return frameworkWithAi(aiPrompt, "[{\"index\":2,\"confidence\":95,\"category\":\"诈骗\",\"reason\":\"兼职刷单引流\"}]",
                (method, payload) -> {
                    rawCalls.incrementAndGet();
                    mutePayload.set(payload);
                }, groupMessages, dmUserIds);
    }

    @SuppressWarnings("unchecked")
    private FrameworkServices frameworkWithAi(AtomicReference<String> aiPrompt, String aiResponse,
                                              java.util.function.BiConsumer<String, Map<String, Object>> rawSink,
                                              List<PluginMessageRequest> groupMessages, List<String> dmUserIds) {
        Object ai = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.ai.PluginAiService")}, (proxy, method, args) -> {
                    if ("chat".equals(method.getName())) {
                        aiPrompt.set(String.valueOf(args[0] == null ? "" : ((online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest) args[0]).userPrompt()));
                        return CompletableFuture.completedFuture(
                                new online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse(aiResponse, List.of()));
                    }
                    if ("providers".equals(method.getName())) {
                        return List.of();
                    }
                    return null;
                });
        Object messaging = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.messaging.PluginMessagingService")}, (proxy, method, args) -> switch (method.getName()) {
                    case "connections" -> List.of();
                    case "send" -> {
                        groupMessages.add((PluginMessageRequest) args[0]);
                        yield CompletableFuture.completedFuture(new PluginMessageResult(List.of("m"), false, false));
                    }
                    case "sendDirectToBoundUser" -> {
                        dmUserIds.add(String.valueOf(args[0]));
                        yield CompletableFuture.completedFuture(new PluginMessageResult(List.of("m"), false, false));
                    }
                    default -> null;
                });
        Object raw = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService")}, (proxy, method, args) -> {
                    if ("invoke".equals(method.getName())) {
                        Map<String, Object> payload = new HashMap<>();
                        ((Map<?, ?>) args[2]).forEach((key, value) -> payload.put(String.valueOf(key), value));
                        payload.put("method", String.valueOf(args[1]));
                        rawSink.accept(String.valueOf(args[1]), payload);
                    }
                    return CompletableFuture.completedFuture(Map.of());
                });
        Object users = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{classForName("online.yudream.base.plugin.spi.system.user.PluginUserService")}, (proxy, method, args) -> Optional.empty());
        return (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "ai" -> ai;
                    case "messaging" -> messaging;
                    case "messagingRaw" -> raw;
                    case "users" -> users;
                    default -> null;
                });
    }

    private Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryDocuments implements online.yudream.base.plugin.spi.system.storage.PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override public synchronized Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(collection + ":" + id, copy);
            return copy;
        }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(collection + ":" + id)).map(HashMap::new);
        }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return values(collection);
        }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return values(collection).size(); }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }

        List<Map<String, Object>> values(String collection) {
            return values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + ":"))
                    .<Map<String, Object>>map(entry -> new HashMap<>(entry.getValue()))
                    .toList();
        }
    }
}
