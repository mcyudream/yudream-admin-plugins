package online.yudream.base.plugin.mcnews.domain;

/**
 * 已发现的新闻条目（缓存视图）。publishedAt 为源提供的发布时间，
 * minecraft.net 列表 JSON 不含发布时间时以 discoveredAt 代替。
 * pushState：pushed 已推送 / failed 推送失败 / skipped 未配置推送目标 / "" 待推送。
 */
public record NewsArticle(
        String id,
        String sourceId,
        String sourceName,
        String title,
        String summary,
        String aiSummary,
        String category,
        String url,
        String imageUrl,
        long publishedAt,
        long discoveredAt,
        long pushedAt,
        String pushState
) {
    public static final String STATE_PUSHED = "pushed";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_SKIPPED = "skipped";
    public static final String STATE_PENDING = "";

    /** 模板 {{body}} 参量：AI 导语优先，回退官方摘要。 */
    public String body() {
        if (aiSummary != null && !aiSummary.isBlank()) {
            return aiSummary;
        }
        return summary == null ? "" : summary;
    }
}
