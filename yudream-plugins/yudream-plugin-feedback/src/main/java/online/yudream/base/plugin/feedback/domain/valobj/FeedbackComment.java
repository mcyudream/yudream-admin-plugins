package online.yudream.base.plugin.feedback.domain.valobj;

import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackCommentRole;

/**
 * 反馈线程中的一条评论。
 *
 * <p>{@code internal} 为真表示仅管理员可见的内部备注：用户端接口不下发这类评论，
 * 也不计入用户可见的评论数。
 */
public record FeedbackComment(
        String id,
        String authorId,
        FeedbackCommentRole authorRole,
        String content,
        boolean internal,
        long createdAt
) {

    /** 是否属于官方（管理员）回复，用于用户端突出展示。 */
    public boolean adminReply() {
        return authorRole == FeedbackCommentRole.ADMIN;
    }
}
