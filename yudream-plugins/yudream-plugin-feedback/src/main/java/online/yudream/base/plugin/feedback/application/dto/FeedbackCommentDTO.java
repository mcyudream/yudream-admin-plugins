package online.yudream.base.plugin.feedback.application.dto;

/**
 * 评论视图。
 *
 * <p>{@code internal} 为真的内部备注不会出现在用户端响应中；{@code adminReply} 用于前端区分
 * 「官方回复」与「用户补充说明」的展示样式。
 */
public record FeedbackCommentDTO(
        String id,
        String authorId,
        String authorName,
        String authorRole,
        boolean adminReply,
        String content,
        boolean internal,
        long createdAt,
        String createdAtLabel
) {
}
