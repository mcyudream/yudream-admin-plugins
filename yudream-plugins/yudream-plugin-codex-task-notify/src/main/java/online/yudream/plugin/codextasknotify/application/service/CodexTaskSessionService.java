package online.yudream.plugin.codextasknotify.application.service;

import online.yudream.plugin.codextasknotify.application.dto.CodexTaskEvent;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskNotification;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskSession;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskStart;
import online.yudream.plugin.codextasknotify.infrastructure.repository.CodexTaskSessionRepository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public class CodexTaskSessionService {

    private static final Logger LOGGER = Logger.getLogger(CodexTaskSessionService.class.getName());
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 900;

    private final CodexTaskSessionRepository sessions;
    private final CodexTaskNotificationService notifications;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public CodexTaskSessionService(CodexTaskSessionRepository sessions, CodexTaskNotificationService notifications) {
        this.sessions = sessions;
        this.notifications = notifications;
    }

    public CodexTaskSession start(Long userId, CodexTaskStart request) {
        String ownerId = requireUserId(userId);
        String taskId = requireText(request == null ? null : request.taskId(), "任务标识", 120);
        String title = requireText(request == null ? null : request.title(), "任务标题", 120);
        int timeoutSeconds = timeout(request == null ? null : request.timeoutSeconds());
        return synchronizedFor(ownerId, taskId, () -> sessions.save(CodexTaskSession.active(
                taskId, ownerId, title, timeoutSeconds, System.currentTimeMillis()
        )));
    }

    public CodexTaskSession heartbeat(Long userId, String taskId) {
        String ownerId = requireUserId(userId);
        String safeTaskId = requireText(taskId, "任务标识", 120);
        return synchronizedFor(ownerId, safeTaskId, () -> {
            CodexTaskSession session = active(ownerId, safeTaskId);
            return sessions.save(session.heartbeat(System.currentTimeMillis()));
        });
    }

    public List<String> event(Long userId, CodexTaskEvent event, CodexTaskNotification.Type type) {
        String ownerId = requireUserId(userId);
        String taskId = requireText(event == null ? null : event.taskId(), "任务标识", 120);
        String title = requireText(event == null ? null : event.title(), "通知标题", 120);
        String message = requireText(event == null ? null : event.message(), "通知内容", 1_200);
        String taskUrl = optional(event == null ? null : event.taskUrl(), "任务链接", 400);
        return synchronizedFor(ownerId, taskId, () -> {
            CodexTaskSession session = active(ownerId, taskId);
            List<String> messageIds = notifications.notify(userId, new CodexTaskNotification(type, taskId, title, message, taskUrl));
            if (type == CodexTaskNotification.Type.COMPLETED) {
                sessions.save(session.complete());
            } else if (type == CodexTaskNotification.Type.INTERRUPTED) {
                sessions.save(session.interrupt());
            }
            return messageIds;
        });
    }

    public void expireDueSessions(long now) {
        for (CodexTaskSession candidate : sessions.findActive()) {
            if (!candidate.expiredAt(now)) {
                continue;
            }
            try {
                synchronizedFor(candidate.userId(), candidate.taskId(), () -> {
                    CodexTaskSession session = sessions.find(candidate.userId(), candidate.taskId()).orElse(null);
                    if (session == null || !session.expiredAt(now)) {
                        return null;
                    }
                    Long userId = Long.valueOf(session.userId());
                    notifications.notify(userId, new CodexTaskNotification(
                            CodexTaskNotification.Type.INTERRUPTED,
                            session.taskId(),
                            session.title(),
                            "任务心跳超时，Codex 可能已断开。",
                            null
                    ));
                    sessions.save(session.expire());
                    return null;
                });
            } catch (RuntimeException exception) {
                LOGGER.warning("[Codex Task Notify] heartbeat timeout notification failed for task " + candidate.taskId());
            }
        }
    }

    private CodexTaskSession active(String ownerId, String taskId) {
        CodexTaskSession session = sessions.find(ownerId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务会话不存在"));
        if (session.status() != CodexTaskSession.Status.ACTIVE) {
            throw new IllegalStateException("任务会话已结束");
        }
        return session;
    }

    private String requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前 API Key 未关联用户");
        }
        return String.valueOf(userId);
    }

    private int timeout(Integer value) {
        int timeout = value == null ? 60 : value;
        if (timeout < MIN_TIMEOUT_SECONDS || timeout > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("心跳超时必须在 " + MIN_TIMEOUT_SECONDS + " 至 " + MAX_TIMEOUT_SECONDS + " 秒之间");
        }
        return timeout;
    }

    private String requireText(String value, String field, int maxLength) {
        String result = optional(value, field, maxLength);
        if (result == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return result;
    }

    private String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过 " + maxLength + " 个字符");
        }
        return result;
    }

    private <T> T synchronizedFor(String userId, String taskId, java.util.function.Supplier<T> action) {
        String key = userId + ":" + taskId;
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
