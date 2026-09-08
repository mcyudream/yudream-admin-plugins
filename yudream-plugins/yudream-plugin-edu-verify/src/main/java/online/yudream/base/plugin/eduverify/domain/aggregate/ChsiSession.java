package online.yudream.base.plugin.eduverify.domain.aggregate;

/** 学信网核验会话（zwfw 流程的 rndid / 图形验证码 uid），id 为 emailLower。 */
public record ChsiSession(
        String id,
        String rndid,
        String captchaUid,
        int attempts,
        String dayBucket,
        long lastCallAt,
        long expiresAt
) {

    public static ChsiSession fresh(String id, String rndid, long now, long ttlMillis) {
        return new ChsiSession(id, rndid, null, 0, null, now, now + ttlMillis);
    }

    public ChsiSession withRndid(String newRndid, long now, long ttlMillis) {
        return new ChsiSession(id, newRndid, null, attempts, dayBucket, now, now + ttlMillis);
    }

    public ChsiSession withCaptchaUid(String newUid) {
        return new ChsiSession(id, rndid, newUid, attempts, dayBucket, lastCallAt, expiresAt);
    }

    public ChsiSession counted(int newAttempts, String newDayBucket, long now) {
        return new ChsiSession(id, rndid, captchaUid, newAttempts, newDayBucket, now, expiresAt);
    }
}
