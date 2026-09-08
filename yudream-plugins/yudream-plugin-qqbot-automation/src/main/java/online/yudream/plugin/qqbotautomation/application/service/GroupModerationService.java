package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 群管理原语（禁言、入群审批），屏蔽 Milky 与官方 QQ 机器人协议的 API 差异。
 * Milky 直连 set_group_member_mute / accept_group_request；官方走适配器的
 * set_group_ban（restrict_chat_setting）与 set_group_add_request（approval_join_request）。
 */
public class GroupModerationService {

    private final FrameworkServices framework;

    public GroupModerationService(FrameworkServices framework) {
        this.framework = framework;
    }

    public boolean isOfficial(String connectionId) {
        try {
            return framework.messaging().connections().stream()
                    .filter(connection -> connection.id().equals(connectionId))
                    .map(PluginMessagingConnection::protocol)
                    .anyMatch("official"::equals);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 禁言群成员；seconds<=0 表示解除禁言。 */
    public CompletionStage<?> mute(String connectionId, String groupId, String userId, long seconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (isOfficial(connectionId)) {
            payload.put("group_id", groupId);
            payload.put("user_id", userId);
            payload.put("duration", seconds);
            return framework.messagingRaw().invoke(connectionId, "set_group_ban", payload);
        }
        // Milky 数值字段要求 int64，字符串 ID 会被拒绝
        payload.put("group_id", parseLong(groupId, "群号"));
        payload.put("user_id", parseLong(userId, "成员 QQ"));
        payload.put("duration", seconds);
        return framework.messagingRaw().invoke(connectionId, "set_group_member_mute", payload);
    }

    /** 官方连接的入群审批；Milky 的审批沿用 accept_group_request/reject_group_request（在 JoinVerificationService）。 */
    public CompletionStage<?> approveJoin(String connectionId, String groupId, String userId, boolean approve) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group_id", groupId);
        payload.put("user_id", userId);
        payload.put("approve", approve);
        return framework.messagingRaw().invoke(connectionId, "set_group_add_request", payload);
    }

    private long parseLong(String value, String name) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Milky 连接的" + name + "必须是数字：" + value);
        }
    }
}
