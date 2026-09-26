package online.yudream.base.plugin.playtimepoints.application.dto;

/** 结算流水视图（REST 输出）。金额为十进制字符串；ID 一律字符串。 */
public record SettlementView(
        String id,
        String serverId,
        String serverName,
        String playerId,
        String playerName,
        String userId,
        Long windowEnd,
        long onlineMillis,
        long afkMillis,
        long effectiveMillis,
        long effectiveMinutes,
        String weight,
        String points,
        String credited,
        String assetCode,
        long createdAt) {
}
