package online.yudream.plugin.codextasknotify.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskNotification;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskEvent;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskSession;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskStart;
import online.yudream.plugin.codextasknotify.application.service.CodexTaskNotificationService;
import online.yudream.plugin.codextasknotify.application.service.CodexTaskSessionService;
import online.yudream.plugin.codextasknotify.interfaces.support.JsonSupport;

import java.util.List;
import java.util.Map;

public class CodexTaskNotifyHttpFacade {

    private final CodexTaskNotificationService notifications;
    private final CodexTaskSessionService sessions;

    public CodexTaskNotifyHttpFacade(CodexTaskNotificationService notifications) {
        this(notifications, null);
    }

    public CodexTaskNotifyHttpFacade(CodexTaskNotificationService notifications, CodexTaskSessionService sessions) {
        this.notifications = notifications;
        this.sessions = sessions;
    }

    public PluginHttpResponse notify(PluginHttpRequest request) {
        Long userId = request.principal() == null ? null : request.principal().userId();
        CodexTaskNotification notification = JsonSupport.read(request.body(), CodexTaskNotification.class);
        List<String> messageIds = notifications.notify(userId, notification);
        return PluginHttpResponse.ok(Map.of("type", notification.type().name(), "messageIds", messageIds));
    }

    public PluginHttpResponse start(PluginHttpRequest request) {
        CodexTaskSession session = sessions.start(userId(request), JsonSupport.read(request.body(), CodexTaskStart.class));
        return PluginHttpResponse.ok(sessionResponse(session));
    }

    public PluginHttpResponse heartbeat(PluginHttpRequest request) {
        CodexTaskEvent event = JsonSupport.read(request.body(), CodexTaskEvent.class);
        CodexTaskSession session = sessions.heartbeat(userId(request), event.taskId());
        return PluginHttpResponse.ok(sessionResponse(session));
    }

    public PluginHttpResponse complete(PluginHttpRequest request) {
        return taskEvent(request, CodexTaskNotification.Type.COMPLETED);
    }

    public PluginHttpResponse actionRequired(PluginHttpRequest request) {
        return taskEvent(request, CodexTaskNotification.Type.ACTION_REQUIRED);
    }

    public PluginHttpResponse interrupt(PluginHttpRequest request) {
        return taskEvent(request, CodexTaskNotification.Type.INTERRUPTED);
    }

    private PluginHttpResponse taskEvent(PluginHttpRequest request, CodexTaskNotification.Type type) {
        CodexTaskEvent event = JsonSupport.read(request.body(), CodexTaskEvent.class);
        return PluginHttpResponse.ok(Map.of("type", type.name(), "messageIds", sessions.event(userId(request), event, type)));
    }

    private Long userId(PluginHttpRequest request) {
        return request.principal() == null ? null : request.principal().userId();
    }

    private Map<String, Object> sessionResponse(CodexTaskSession session) {
        return Map.of(
                "taskId", session.taskId(),
                "timeoutSeconds", session.timeoutSeconds(),
                "lastHeartbeatAt", session.lastHeartbeatAt(),
                "status", session.status().name()
        );
    }
}
