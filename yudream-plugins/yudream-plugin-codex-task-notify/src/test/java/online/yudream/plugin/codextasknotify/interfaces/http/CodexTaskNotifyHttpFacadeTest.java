package online.yudream.plugin.codextasknotify.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.plugin.codextasknotify.application.service.CodexTaskNotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexTaskNotifyHttpFacadeTest {

    @Test
    void sendsToTheAuthenticatedPrincipalOnly() {
        CapturingMessagingService messaging = new CapturingMessagingService();
        CodexTaskNotifyHttpFacade facade = new CodexTaskNotifyHttpFacade(new CodexTaskNotificationService(messaging));

        facade.notify(request("{\"type\":\"COMPLETED\",\"title\":\"Build completed\",\"message\":\"Package passed\"}", 42L));

        assertEquals("42", messaging.recipientUserId);
        assertTrue(messaging.content.content().contains("Build completed"));
        assertTrue(messaging.content.content().contains("完成总结"));
    }

    @Test
    void rejectsAClientSuppliedRecipient() {
        CapturingMessagingService messaging = new CapturingMessagingService();
        CodexTaskNotifyHttpFacade facade = new CodexTaskNotifyHttpFacade(new CodexTaskNotificationService(messaging));

        assertThrows(IllegalArgumentException.class, () -> facade.notify(request(
                "{\"type\":\"COMPLETED\",\"title\":\"Build completed\",\"message\":\"Package passed\",\"userId\":\"99\"}",
                42L
        )));
    }

    private PluginHttpRequest request(String body, Long userId) {
        return new PluginHttpRequest("POST", "/notify", Map.of(), Map.of(), body, new PluginPrincipal(userId, List.of("*")));
    }

    private static final class CapturingMessagingService implements PluginMessagingService {

        private String recipientUserId;
        private PluginMessageContent content;

        @Override
        public List<PluginMessagingConnection> connections() {
            return List.of();
        }

        @Override
        public List<PluginMessagingGroup> groups(String connectionId) {
            return List.of();
        }

        @Override
        public CompletionStage<PluginMessageResult> send(PluginMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<PluginMessageResult> sendDirectToBoundUser(String userId, PluginMessageContent content) {
            this.recipientUserId = userId;
            this.content = content;
            return CompletableFuture.completedFuture(new PluginMessageResult(List.of("message-1"), false, false));
        }

        @Override
        public CompletionStage<PluginMessageResult> sendToChannel(String connectionId, String channelId, PluginMessageContent content) {
            throw new UnsupportedOperationException();
        }
    }
}
