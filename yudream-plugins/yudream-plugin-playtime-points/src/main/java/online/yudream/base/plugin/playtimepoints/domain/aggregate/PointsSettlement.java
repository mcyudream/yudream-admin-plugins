package online.yudream.base.plugin.playtimepoints.domain.aggregate;

import online.yudream.base.plugin.playtimepoints.domain.valobj.SubSettlement;

import java.util.List;

/**
 * 一次退出结算的流水记录。金额类字段（weight/points/credited/carryAfter）以十进制字符串保存，避免文档存储丢精度。
 *
 * <p>{@code subServers} 是本次结算按子服的拆分：没有子服维度（整服口径、提供方旧版本或改造前的
 * 流水文档）时为空列表。顶层 onlineMillis/afkMillis/effectiveMillis 永远是各子服明细的合计，
 * 只有子服权重参与的 points 由「Σ(子服有效分钟 × 子服权重) ÷ 每积分分钟数」得出。
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
        long createdAt,
        List<SubSettlement> subServers) {

    public PointsSettlement {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 兼容旧调用方：整服口径结算，没有子服明细。 */
    public PointsSettlement(String id, String serverId, String serverName, String playerId, String playerName,
                            String userId, Long windowStart, long windowEnd, long onlineMillis, long afkMillis,
                            long effectiveMillis, String weight, String points, String credited, String carryAfter,
                            String assetCode, String businessNo, long createdAt) {
        this(id, serverId, serverName, playerId, playerName, userId, windowStart, windowEnd, onlineMillis, afkMillis,
                effectiveMillis, weight, points, credited, carryAfter, assetCode, businessNo, createdAt, List.of());
    }
}
