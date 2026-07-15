package online.yudream.plugin.codextasknotify.application.service;

import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskSession;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskStart;
import online.yudream.plugin.codextasknotify.infrastructure.repository.CodexTaskSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexTaskSessionServiceTest {

    @Test
    void timesOutTheOwnerSessionAndSendsOneInterruptionNotice() {
        CapturingMessaging messaging = new CapturingMessaging();
        CodexTaskSessionService service = service(messaging);
        CodexTaskSession session = service.start(42L, new CodexTaskStart("task-1", "Plugin build", 30));

        service.expireDueSessions(session.lastHeartbeatAt() + 30_000L);

        assertEquals("42", messaging.recipientUserId);
        assertTrue(messaging.content.content().contains("Plugin build"));
        assertThrows(IllegalStateException.class, () -> service.heartbeat(42L, "task-1"));
    }

    @Test
    void doesNotAllowAnotherUserToRefreshTheSession() {
        CodexTaskSessionService service = service(new CapturingMessaging());
        service.start(42L, new CodexTaskStart("task-1", "Plugin build", 60));

        assertThrows(IllegalArgumentException.class, () -> service.heartbeat(99L, "task-1"));
    }

    private CodexTaskSessionService service(CapturingMessaging messaging) {
        return new CodexTaskSessionService(
                new CodexTaskSessionRepository(new InMemoryDocuments()),
                new CodexTaskNotificationService(messaging)
        );
    }

    private static final class CapturingMessaging implements PluginMessagingService {
        private String recipientUserId;
        private PluginMessageContent content;

        @Override public List<PluginMessagingConnection> connections() { return List.of(); }
        @Override public List<PluginMessagingGroup> groups(String connectionId) { return List.of(); }
        @Override public CompletionStage<PluginMessageResult> send(PluginMessageRequest request) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<PluginMessageResult> sendToChannel(String connectionId, String channelId, PluginMessageContent content) { throw new UnsupportedOperationException(); }

        @Override
        public CompletionStage<PluginMessageResult> sendDirectToBoundUser(String userId, PluginMessageContent content) {
            this.recipientUserId = userId;
            this.content = content;
            return CompletableFuture.completedFuture(new PluginMessageResult(List.of("message-1"), false, false));
        }
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            copy.put("id", id);
            values.put(collection + ":" + id, copy);
            return copy;
        }

        @Override public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.ofNullable(values.get(collection + ":" + id)); }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return new ArrayList<>(values.values()); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return values.size(); }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }
    }
}
