package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 用户端活动答题视图。sessionId 非空表示存在进行中的作答会话（可跳转题库作答页继续）；
 * sessionStatus/correctCount/totalCount/pendingReview 仅在最近一次会话存在且有结果时返回。
 */
public record ActivityQuizViewDTO(
        boolean enabled,
        boolean available,
        boolean joined,
        int count,
        int passCorrect,
        String subjectiveMode,
        int attempts,
        boolean passed,
        String sessionId,
        String sessionStatus,
        Integer correctCount,
        Integer totalCount,
        boolean pendingReview
) {
    public static ActivityQuizViewDTO disabled() {
        return new ActivityQuizViewDTO(false, false, false, 0, 0, null, 0, false, null, null, null, null, false);
    }
}
