package online.yudream.base.plugin.playtimepoints.domain.aggregate;

/**
 * 单个玩家在单台服务器上的结算基线：记录上次结算时 mc-server 侧的累计在线/挂机毫秒，
 * 下次退出时用累计值差值结算本次会话，mc-server 侧数据重置也不会产生负积分。
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
        long updatedAt) {
}
