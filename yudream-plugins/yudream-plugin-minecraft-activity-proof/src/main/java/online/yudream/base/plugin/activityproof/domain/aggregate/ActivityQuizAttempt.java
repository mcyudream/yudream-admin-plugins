package online.yudream.base.plugin.activityproof.domain.aggregate;

/** 活动答题进度：记录用户在某活动下最近一次题库作答会话与达标状态。 */
public record ActivityQuizAttempt(
        String id,
        String activityId,
        String userId,
        String sessionId,
        int attempts,
        boolean passed,
        long passedAt,
        long updatedAt
) {
    public ActivityQuizAttempt {
        activityId = require(activityId, "活动不能为空");
        userId = require(userId, "用户不能为空");
        sessionId = sessionId == null ? "" : sessionId.trim();
        id = id == null || id.isBlank() ? id(activityId, userId) : id.trim();
    }

    public static ActivityQuizAttempt create(String activityId, String userId, String sessionId) {
        return new ActivityQuizAttempt(null, activityId, userId, sessionId, 1, false, 0, System.currentTimeMillis());
    }

    public ActivityQuizAttempt withSession(String newSessionId) {
        return new ActivityQuizAttempt(id, activityId, userId, newSessionId, attempts + 1, passed, passedAt,
                System.currentTimeMillis());
    }

    public ActivityQuizAttempt markPassed() {
        return new ActivityQuizAttempt(id, activityId, userId, sessionId, attempts, true,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public static String id(String activityId, String userId) {
        return require(activityId, "活动不能为空") + ":" + require(userId, "用户不能为空");
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
