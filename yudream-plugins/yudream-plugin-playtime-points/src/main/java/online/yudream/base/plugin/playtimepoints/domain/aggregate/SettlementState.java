package online.yudream.base.plugin.playtimepoints.domain.aggregate;

import online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline;

import java.util.Map;

/**
 * 单个玩家在单台服务器上的结算基线：记录上次结算时 mc-server 侧的累计在线/挂机毫秒，
 * 下次退出时用累计值差值结算本次会话，mc-server 侧数据重置也不会产生负积分。
 *
 * <p>{@code subServers} 是同一份基线在群组服下的子服拆分：mc-server 上报子服维度时按子服求差值
 * 并按各子服权重计分；按子服为键的累计值与整服累计值同步推进。改造之前写入的文档没有该字段，
 * 读出来是空表，此时按整服口径结算，行为与改造前一致。
 *
 * @param id serverId:playerId
 */
public record SettlementState(
        String id,
        String serverId,
        String playerId,
        String playerName,
        long settledOnlineMillis,
        long settledAfkMillis,
        Long settledQuitAt,
        String carryPoints,
        long updatedAt,
        Map<String, SubServerBaseline> subServers) {

    public SettlementState {
        subServers = subServers == null ? Map.of() : Map.copyOf(subServers);
    }

    /** 兼容旧调用方：没有子服基线。 */
    public SettlementState(String id, String serverId, String playerId, String playerName,
                           long settledOnlineMillis, long settledAfkMillis, Long settledQuitAt,
                           String carryPoints, long updatedAt) {
        this(id, serverId, playerId, playerName, settledOnlineMillis, settledAfkMillis, settledQuitAt,
                carryPoints, updatedAt, Map.of());
    }
}
