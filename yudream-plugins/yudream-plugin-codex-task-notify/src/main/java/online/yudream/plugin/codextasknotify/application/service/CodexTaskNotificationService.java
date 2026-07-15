package online.yudream.plugin.codextasknotify.application.service;

import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskNotification;

import java.util.List;
import java.util.concurrent.CompletionException;

public class CodexTaskNotificationService {

    private static final int TITLE_MAX_LENGTH = 120;
    private static final int MESSAGE_MAX_LENGTH = 1_200;
    private static final int TASK_ID_MAX_LENGTH = 120;
    private static final int TASK_URL_MAX_LENGTH = 400;
    private static final int RENDERED_MESSAGE_MAX_LENGTH = 1_500;

    private final PluginMessagingService messaging;

    public CodexTaskNotificationService(PluginMessagingService messaging) {
        this.messaging = messaging;
    }

    public List<String> notify(Long userId, CodexTaskNotification notification) {
        if (userId == null) {
            throw new IllegalArgumentException("当前 API Key 未关联用户");
        }
        String content = render(validate(notification));
        try {
            PluginMessageResult result = messaging.sendDirectToBoundUser(
                    String.valueOf(userId),
                    new PluginMessageContent(PluginMessageContent.Type.TEXT, content, null, null)
            ).toCompletableFuture().join();
            return result.messageIds();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Codex 通知发送失败", cause);
        }
    }

    private CodexTaskNotification validate(CodexTaskNotification notification) {
        if (notification == null || notification.type() == null) {
            throw new IllegalArgumentException("通知类型不能为空");
        }
        require(notification.title(), "通知标题", TITLE_MAX_LENGTH);
        require(notification.message(), "通知内容", MESSAGE_MAX_LENGTH);
        optional(notification.taskId(), "任务标识", TASK_ID_MAX_LENGTH);
        optional(notification.taskUrl(), "任务链接", TASK_URL_MAX_LENGTH);
        return notification;
    }

    private String render(CodexTaskNotification notification) {
        StringBuilder content = new StringBuilder("[Codex] ")
                .append(notification.type().label())
                .append('\n')
                .append(notification.title().trim());
        if (notification.type() == CodexTaskNotification.Type.COMPLETED) {
            content.append("\n完成总结：\n");
        } else {
            content.append('\n');
        }
        content.append(notification.message().trim());
        if (hasText(notification.taskId())) {
            content.append("\n任务：").append(notification.taskId().trim());
        }
        if (hasText(notification.taskUrl())) {
            content.append("\n链接：").append(notification.taskUrl().trim());
        }
        if (content.length() > RENDERED_MESSAGE_MAX_LENGTH) {
            throw new IllegalArgumentException("通知内容不能超过 " + RENDERED_MESSAGE_MAX_LENGTH + " 个字符");
        }
        return content.toString();
    }

    private void require(String value, String field, int maxLength) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        optional(value, field, maxLength);
    }

    private void optional(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过 " + maxLength + " 个字符");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
