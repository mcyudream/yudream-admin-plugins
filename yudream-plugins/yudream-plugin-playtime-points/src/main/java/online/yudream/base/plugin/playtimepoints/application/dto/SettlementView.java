package online.yudream.base.plugin.playtimepoints.application.dto;

import java.util.List;

/**
 * 结算流水视图（REST 输出）。金额为十进制字符串；ID 一律字符串。
 *
 * <p>{@code subServers} 为群组服下的子服拆分；整服口径（没有子服维度、提供方旧版本或改造前的
 * 流水文档）为空列表，此时 {@code weight} 就是该服务器的权重，行内积分由整服口径得出。
 */
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
        long createdAt,
        List<SubSettlementView> subServers) {
}
