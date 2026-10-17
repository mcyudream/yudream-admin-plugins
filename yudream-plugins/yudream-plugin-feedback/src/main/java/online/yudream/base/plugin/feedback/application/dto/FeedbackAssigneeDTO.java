package online.yudream.base.plugin.feedback.application.dto;

/**
 * 处理人视图。
 *
 * <p>一条反馈可以有多个处理人（对应 GitHub Issue 的多个 assignee）；这里只给出展示所需的
 * 身份信息，不回传邮箱、状态等账号目录字段，避免把无关的人员属性散播到反馈数据里。
 */
public record FeedbackAssigneeDTO(
        String id,
        String name
) {

    /** 展示名缺失时回退为空串，由前端按 ID 兜底展示。 */
    public static FeedbackAssigneeDTO of(String id, String name) {
        return new FeedbackAssigneeDTO(id, name == null ? "" : name);
    }
}
