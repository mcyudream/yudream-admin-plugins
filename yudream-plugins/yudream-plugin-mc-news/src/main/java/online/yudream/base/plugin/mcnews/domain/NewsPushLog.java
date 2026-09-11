package online.yudream.base.plugin.mcnews.domain;

import java.util.List;

/**
 * 一次推送动作的记录：一条新闻 × 一批推送目标。mode：poll 定时 / manual 手动 / test 测试。
 */
public record NewsPushLog(
        String id,
        long createdAt,
        String mode,
        String articleId,
        String title,
        String url,
        String sourceName,
        int total,
        int okCount,
        List<TargetResult> results
) {
    public NewsPushLog {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record TargetResult(String targetId, String targetName, String targetType, boolean ok, String error) {
    }
}
