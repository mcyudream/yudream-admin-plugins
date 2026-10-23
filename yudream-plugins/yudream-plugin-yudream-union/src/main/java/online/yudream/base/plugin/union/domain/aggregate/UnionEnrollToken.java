package online.yudream.base.plugin.union.domain.aggregate;

/**
 * 一次性信任注册令牌（文档主键 = 明文 token 的 SHA-256；明文只在签发响应出现一次）。
 *
 * @param id         SHA-256(token) hex
 * @param createdBy  签发管理员
 * @param createdAt  签发时间（epoch 毫秒）
 * @param expiresAt  过期时间
 * @param consumed   是否已被注册请求消费
 * @param consumedAt 消费时间（可空）
 */
public record UnionEnrollToken(
        String id,
        String createdBy,
        long createdAt,
        long expiresAt,
        boolean consumed,
        Long consumedAt
) {
}
