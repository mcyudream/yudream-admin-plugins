package online.yudream.plugin.codextasknotify.application.dto;

public record CodexTaskSession(
        String taskId,
        String userId,
        String title,
        int timeoutSeconds,
        long lastHeartbeatAt,
        Status status
) {

    public enum Status {
        ACTIVE,
        COMPLETED,
        INTERRUPTED,
        EXPIRED
    }

    public static CodexTaskSession active(String taskId, String userId, String title, int timeoutSeconds, long now) {
        return new CodexTaskSession(taskId, userId, title, timeoutSeconds, now, Status.ACTIVE);
    }

    public CodexTaskSession heartbeat(long now) {
        return new CodexTaskSession(taskId, userId, title, timeoutSeconds, now, Status.ACTIVE);
    }

    public CodexTaskSession complete() {
        return withStatus(Status.COMPLETED);
    }

    public CodexTaskSession interrupt() {
        return withStatus(Status.INTERRUPTED);
    }

    public CodexTaskSession expire() {
        return withStatus(Status.EXPIRED);
    }

    public boolean expiredAt(long now) {
        return status == Status.ACTIVE && now >= lastHeartbeatAt + timeoutSeconds * 1_000L;
    }

    private CodexTaskSession withStatus(Status nextStatus) {
        return new CodexTaskSession(taskId, userId, title, timeoutSeconds, lastHeartbeatAt, nextStatus);
    }
}
