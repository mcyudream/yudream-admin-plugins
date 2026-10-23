package online.yudream.base.plugin.activityproof.api;

/** 活动只读摘要，供下游文档生成等场景使用。时间字段为 epoch millis，0 表示未设置。 */
public record ActivityBrief(
        String id,
        String title,
        String summary,
        String description,
        String coverUrl,
        String status,
        long signupStart,
        long signupEnd,
        long activityStart,
        long activityEnd,
        long publishedAt,
        long participantCount
) {
}
