package online.yudream.base.plugin.eduverify.domain.aggregate;

/** 教育邮箱验证码记录，id 为 emailLower。 */
public record EmailCode(
        String id,
        String codeHash,
        long expiresAt,
        int attempts,
        int sendCount,
        String dayBucket,
        long lastSendAt,
        long createdAt
) {

    public EmailCode nextSend(String newCodeHash, long newExpiresAt, int newSendCount, String newDayBucket, long now) {
        return new EmailCode(id, newCodeHash, newExpiresAt, 0, newSendCount, newDayBucket, now, createdAt);
    }

    public EmailCode withAttempt(int newAttempts) {
        return new EmailCode(id, codeHash, expiresAt, newAttempts, sendCount, dayBucket, lastSendAt, createdAt);
    }
}
