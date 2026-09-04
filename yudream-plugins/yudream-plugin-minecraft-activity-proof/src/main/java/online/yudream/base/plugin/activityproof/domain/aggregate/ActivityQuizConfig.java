package online.yudream.base.plugin.activityproof.domain.aggregate;

import java.util.List;

/**
 * 活动答题配置（软依赖题库插件）：按规则现场随机抽题，答对 passCorrect 题视为达标。
 * subjectiveMode：SELF 用户自评；REVIEW 管理员人工审核；AI 题库侧 AI 自动判分（失败回落人工审核）。
 */
public record ActivityQuizConfig(
        String activityId,
        boolean enabled,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        int count,
        int passCorrect,
        String subjectiveMode,
        long updatedAt
) {
    public static final String SUBJECTIVE_SELF = "SELF";
    public static final String SUBJECTIVE_REVIEW = "REVIEW";
    public static final String SUBJECTIVE_AI = "AI";
    public static final int MAX_COUNT = 50;

    public ActivityQuizConfig {
        activityId = activityId == null ? "" : activityId.trim();
        categoryId = categoryId == null ? "" : categoryId.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        types = types == null ? List.of() : List.copyOf(types);
        difficulties = difficulties == null ? List.of() : List.copyOf(difficulties);
        subjectiveMode = subjectiveMode == null || subjectiveMode.isBlank()
                ? SUBJECTIVE_SELF : subjectiveMode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static ActivityQuizConfig disabled(String activityId) {
        return new ActivityQuizConfig(activityId, false, "", List.of(), List.of(), List.of(),
                0, 0, SUBJECTIVE_SELF, 0);
    }
}
