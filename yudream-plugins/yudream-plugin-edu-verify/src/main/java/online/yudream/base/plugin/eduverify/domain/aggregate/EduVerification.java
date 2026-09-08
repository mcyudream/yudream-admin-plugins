package online.yudream.base.plugin.eduverify.domain.aggregate;

import java.util.List;
import java.util.Map;

/**
 * 学历认证记录。预注册阶段以 emailLower 为归属键，注册成功后由 AuthEventListener 绑定 userId。
 * id 结构：{channel}:{emailLower}，同一邮箱同一渠道只保留一条最新记录。
 */
public record EduVerification(
        String id,
        String emailLower,
        String userId,
        String channel,
        String status,
        String realName,
        String schoolName,
        String note,
        String vcode,
        List<Map<String, Object>> materials,
        String reason,
        long submittedAt,
        long decidedAt,
        String decidedBy,
        long expiresAt,
        long createdAt,
        long updatedAt
) {

    public EduVerification {
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    public static String idOf(String channel, String emailLower) {
        return channel + ":" + emailLower;
    }

    public boolean passed(long now) {
        return "PASSED".equals(status) && (expiresAt <= 0 || expiresAt > now);
    }

    public boolean pendingMail(long now) {
        return "PENDING_MAIL".equals(status) && (expiresAt <= 0 || expiresAt > now);
    }

    public boolean mailWaitExpired(long now) {
        return "PENDING_MAIL".equals(status) && expiresAt > 0 && expiresAt <= now;
    }

    public EduVerification withUserId(String newUserId, long now) {
        return new EduVerification(id, emailLower, newUserId, channel, status, realName, schoolName, note, vcode,
                materials, reason, submittedAt, decidedAt, decidedBy, expiresAt, createdAt, now);
    }

    public EduVerification withIdentity(String newRealName, String newSchoolName, long now) {
        return new EduVerification(id, emailLower, userId, channel, status, newRealName, newSchoolName, note, vcode,
                materials, reason, submittedAt, decidedAt, decidedBy, expiresAt, createdAt, now);
    }

    public EduVerification decided(String newStatus, String decidedByUser, String newReason, long newExpiresAt, long now) {
        return new EduVerification(id, emailLower, userId, channel, newStatus, realName, schoolName, note, vcode,
                materials, newReason, submittedAt, now, decidedByUser, newExpiresAt, createdAt, now);
    }

    public EduVerification decided(
            String newStatus,
            String decidedByUser,
            String newReason,
            String newRealName,
            String newSchoolName,
            long newExpiresAt,
            long now
    ) {
        return new EduVerification(id, emailLower, userId, channel, newStatus, newRealName, newSchoolName, note, vcode,
                materials, newReason, submittedAt, now, decidedByUser, newExpiresAt, createdAt, now);
    }

    public EduVerification withMaterials(List<Map<String, Object>> newMaterials, String newVcode, long now) {
        return new EduVerification(id, emailLower, userId, channel, status, realName, schoolName, note, newVcode,
                newMaterials, reason, submittedAt, decidedAt, decidedBy, expiresAt, createdAt, now);
    }

    public EduVerification withReason(String newReason, long now) {
        return new EduVerification(id, emailLower, userId, channel, status, realName, schoolName, note, vcode,
                materials, newReason, submittedAt, decidedAt, decidedBy, expiresAt, createdAt, now);
    }
}
