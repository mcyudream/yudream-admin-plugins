package online.yudream.base.plugin.skin.domain.aggregate;

/**
 * 正版账号绑定（Mojang Java profile 所有权验证后建立；键 = 官方 UUID）。
 *
 * @param officialUuid       官方 profile UUID（无连字符，绑定主键）
 * @param ownerId            宿主用户主键（string）
 * @param officialNameLast   验证时官方角色名快照（展示用；以官方为准，续签时同步）
 * @param verificationMethod microsoft-oauth | join-challenge
 * @param xuid               Xbox 用户主键（string，可空）
 * @param msRefreshStored    Microsoft refresh_token 是否已入 SecretStore（自带续签）
 * @param status             active | unbound
 * @param verifiedAt         所有权验证时间
 * @param createdAt          绑定时间
 * @param unboundAt          解绑时间（可空）
 */
public record OfficialBind(
        String officialUuid,
        String ownerId,
        String officialNameLast,
        String verificationMethod,
        String xuid,
        boolean msRefreshStored,
        String status,
        long verifiedAt,
        long createdAt,
        Long unboundAt
) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_UNBOUND = "unbound";

    public OfficialBind withName(String officialNameLast, String xuid, boolean msRefreshStored) {
        return new OfficialBind(officialUuid, ownerId, officialNameLast, verificationMethod, xuid,
                msRefreshStored, status, System.currentTimeMillis(), createdAt, unboundAt);
    }
}
