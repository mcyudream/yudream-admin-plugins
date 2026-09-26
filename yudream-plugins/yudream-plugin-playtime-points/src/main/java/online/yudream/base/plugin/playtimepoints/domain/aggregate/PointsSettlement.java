package online.yudream.base.plugin.playtimepoints.domain.aggregate;

/**
 * 一次退出结算的流水记录。金额类字段（weight/points/credited/carryAfter）以十进制字符串保存，避免文档存储丢精度。
 *
 * @param windowStart 上一次已结算的退出时间（基线为 null）
 * @param windowEnd   本次退出时间
 */
public record PointsSettlement(
        String id,
        String serverId,
        String serverName,
        String playerId,
        String playerName,
        String userId,
        Long windowStart,
        long windowEnd,
        long onlineMillis,
        long afkMillis,
        long effectiveMillis,
        String weight,
        String points,
        String credited,
        String carryAfter,
        String assetCode,
        String businessNo,
        long createdAt) {
}
