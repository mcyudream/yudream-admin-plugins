package online.yudream.base.plugin.feedback.domain.enumerate;

import java.util.Locale;

/**
 * 评论作者角色。
 *
 * <p>反馈是用户与管理员双向沟通的线程：同一份评论列表里要能区分「用户补充说明」与「官方回复」，
 * 用户端不展示内部备注，也不应把内部备注误当成官方答复。
 */
public enum FeedbackCommentRole {

    USER("user", "用户"),
    ADMIN("admin", "管理员");

    private final String code;
    private final String label;

    FeedbackCommentRole(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static FeedbackCommentRole of(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (FeedbackCommentRole role : values()) {
            if (role.code.equals(normalized)) {
                return role;
            }
        }
        return USER;
    }
}
