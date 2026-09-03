package online.yudream.base.plugin.activityproof.domain.aggregate;

import online.yudream.base.plugin.activityproof.domain.enumerate.ParticipationStatus;
import online.yudream.base.plugin.activityproof.domain.enumerate.VerifyStatus;

public record ActivityParticipation(
        String id,
        String activityId,
        String userId,
        ParticipationStatus status,
        long joinedAt,
        long cancelledAt,
        VerifyStatus verifyStatus,
        long verifiedAt,
        String verifyNote
) {
    public ActivityParticipation {
        activityId = require(activityId, "活动不能为空");
        userId = require(userId, "用户不能为空");
        status = status == null ? ParticipationStatus.JOINED : status;
        verifyStatus = verifyStatus == null ? VerifyStatus.UNVERIFIED : verifyStatus;
        verifyNote = verifyNote == null ? "" : verifyNote.trim();
        id = id == null || id.isBlank() ? id(activityId, userId) : id.trim();
    }

    public static ActivityParticipation create(String activityId, String userId, boolean autoPassed) {
        long now = System.currentTimeMillis();
        return new ActivityParticipation(null, activityId, userId, ParticipationStatus.JOINED, now, 0,
                autoPassed ? VerifyStatus.PASSED : VerifyStatus.UNVERIFIED,
                autoPassed ? now : 0,
                autoPassed ? "参与活动即达标" : "");
    }

    public ActivityParticipation rejoin(boolean autoPassed) {
        long now = System.currentTimeMillis();
        return new ActivityParticipation(id, activityId, userId, ParticipationStatus.JOINED, now, 0,
                autoPassed ? VerifyStatus.PASSED : VerifyStatus.UNVERIFIED,
                autoPassed ? now : 0,
                autoPassed ? "参与活动即达标" : "");
    }

    public ActivityParticipation cancel() {
        if (status == ParticipationStatus.CANCELLED) {
            return this;
        }
        return new ActivityParticipation(id, activityId, userId, ParticipationStatus.CANCELLED, joinedAt,
                System.currentTimeMillis(), verifyStatus, verifiedAt, verifyNote);
    }

    public ActivityParticipation withVerification(VerifyStatus nextStatus, String note) {
        return new ActivityParticipation(id, activityId, userId, status, joinedAt, cancelledAt,
                nextStatus, System.currentTimeMillis(), note == null ? "" : note.trim());
    }

    public boolean isJoined() {
        return status == ParticipationStatus.JOINED;
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
