package online.yudream.base.plugin.feedback.application.dto;

/**
 * 处理留痕视图。
 *
 * <p>与评论在前端按 {@code createdAt} 合并成同一条时间线，因此这里只给出动作与补充说明。
 */
public record FeedbackActivityDTO(
        String actorId,
        String actorName,
        String action,
        String actionLabel,
        String detail,
        long createdAt,
        String createdAtLabel
) {
}
