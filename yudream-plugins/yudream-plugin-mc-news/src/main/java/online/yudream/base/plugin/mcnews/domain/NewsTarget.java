package online.yudream.base.plugin.mcnews.domain;

import java.util.List;

/**
 * 推送目标。type=messaging 表示宿主消息连接下的群聊频道；
 * type=webhook 表示通用 HTTP Webhook。ownerUserId 非空表示用户个人目标，
 * 空表示管理员维护的全局目标。
 */
public record NewsTarget(
        String id,
        String name,
        String type,
        boolean enabled,
        String ownerUserId,
        String connectionId,
        String channelId,
        String channelName,
        String webhookUrl,
        List<WebhookHeader> headers,
        boolean markdown,
        long createdAt
) {
    public static final String TYPE_MESSAGING = "messaging";
    public static final String TYPE_WEBHOOK = "webhook";

    public NewsTarget {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    public record WebhookHeader(String key, String value) {
    }

    public boolean messaging() {
        return TYPE_MESSAGING.equals(type);
    }

    /** 个人目标归当前用户所有。 */
    public boolean ownedBy(String userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }
}
