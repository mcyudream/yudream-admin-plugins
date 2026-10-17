package online.yudream.base.plugin.feedback.interfaces.request;

import java.util.List;

/**
 * 处理人请求：整体替换处理人名单，空数组表示清空。
 *
 * <p>请求体一律用 record：宿主 ObjectMapper 关闭了未知字段报错，普通类 + 无 setter 的访问器
 * 会让 Jackson 探测不到属性，入参静默变全 null。
 */
public record FeedbackAssigneeRequest(List<String> assigneeIds) {

    public List<String> safeAssigneeIds() {
        if (assigneeIds == null) {
            return List.of();
        }
        return assigneeIds.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
