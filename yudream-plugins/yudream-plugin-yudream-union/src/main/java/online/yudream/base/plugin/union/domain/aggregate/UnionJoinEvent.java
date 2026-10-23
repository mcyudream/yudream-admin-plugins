package online.yudream.base.plugin.union.domain.aggregate;

/**
 * 跨站登录事件（append-only，可溯源）。
 *
 * @param id          事件主键 = 断言 jti（inbound/outbound 同一份）
 * @param direction   inbound（本站验证的对端玩家）| outbound（本站签发给 aud 站的断言）
 * @param siteCode    对端站点：inbound = 断言签发站；outbound = aud 受众站
 * @param profileId   角色 UUID（无连字符）
 * @param profileName 角色名
 * @param serverId    服务器 serverHash
 * @param issuerKeyId 断言签发方公钥指纹
 * @param recordedAt  本站记录时间（epoch 毫秒）
 */
public record UnionJoinEvent(
        String id,
        String direction,
        String siteCode,
        String profileId,
        String profileName,
        String serverId,
        String issuerKeyId,
        long recordedAt
) {

    public static final String DIRECTION_INBOUND = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";
}
