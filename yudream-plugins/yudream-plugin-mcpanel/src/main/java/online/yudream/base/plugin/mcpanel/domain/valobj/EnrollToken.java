package online.yudream.base.plugin.mcpanel.domain.valobj;

/**
 * 一次性注册令牌。明文 token 只在签发响应出现一次，落库仅存 SHA-256 摘要；
 * 兑换以 redeemedAtMs 的 CAS（0 → now）保证全局仅一次。
 */
public record EnrollToken(
        String id,
        String nodeId,
        String tokenDigest,
        long expiresAtMs,
        long redeemedAtMs,
        long createdAtMs) {

    /** 令牌有效期为 [createdAt, expiresAt)：到达过期时刻即不可用（重签吊销把 expiresAtMs 置为 now 立即失效）。 */
    public boolean expired(long nowMs) {
        return nowMs >= expiresAtMs;
    }

    public boolean redeemed() {
        return redeemedAtMs > 0;
    }

    public EnrollToken withRedeemed(long nowMs) {
        return new EnrollToken(id, nodeId, tokenDigest, expiresAtMs, nowMs, createdAtMs);
    }

    /** 重签吊销：仅把过期时间提前到 now（redeemedAtMs 保持 0，由 expireOnce 的 CAS 决定生效）。 */
    public EnrollToken withExpiredAt(long nowMs) {
        return new EnrollToken(id, nodeId, tokenDigest, nowMs, redeemedAtMs, createdAtMs);
    }
}
