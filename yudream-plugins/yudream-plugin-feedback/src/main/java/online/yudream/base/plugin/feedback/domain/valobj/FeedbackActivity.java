package online.yudream.base.plugin.feedback.domain.valobj;

import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackActivityAction;

/**
 * 处理过程留痕条目。
 *
 * <p>只追加、不修改，因此不需要独立 ID：展示时按 {@code createdAt} 与评论合并排序即可。
 * {@code detail} 存放人类可读的补充说明（例如「已解决 → 原因：已在 1.4.2 修复」），可为空。
 */
public record FeedbackActivity(
        String actorId,
        FeedbackActivityAction action,
        String detail,
        long createdAt
) {

    public static FeedbackActivity of(String actorId, FeedbackActivityAction action, String detail, long now) {
        String normalized = detail == null ? "" : detail.trim();
        return new FeedbackActivity(actorId, action, normalized, now);
    }
}
