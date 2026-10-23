package online.yudream.base.plugin.union.domain.aggregate;

/**
 * 联合封禁条目（键 = 规范化角色名；本站管理员封禁与受信站拉取共用一表）。
 *
 * @param seq        站内单调序号（账本游标）
 * @param name       原始角色名
 * @param nameLower  规范化键（trim + 小写 + 去颜色码）
 * @param reason     封禁原因（面向审计，不含个人信息）
 * @param sourceSite 封禁来源站 siteCode（本站封禁 = 本站 code）
 * @param evidence   证据摘要（可空）
 * @param createdBy  操作人
 * @param createdAt  创建时间（epoch 毫秒）
 * @param expiresAt  过期时间（可空 = 永久）
 * @param status     active | lifted
 * @param liftedBy   解除人（可空）
 * @param liftedAt   解除时间（可空）
 */
public record UnionBan(
        long seq,
        String name,
        String nameLower,
        String reason,
        String sourceSite,
        String evidence,
        String createdBy,
        long createdAt,
        Long expiresAt,
        String status,
        String liftedBy,
        Long liftedAt
) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_LIFTED = "lifted";

    public boolean activeAt(long now) {
        return STATUS_ACTIVE.equals(status) && (expiresAt == null || expiresAt > now);
    }
}
