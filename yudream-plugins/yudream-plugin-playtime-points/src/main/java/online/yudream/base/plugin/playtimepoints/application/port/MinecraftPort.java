package online.yudream.base.plugin.playtimepoints.application.port;

import java.util.List;

/** minecraft-server 插件端口：只暴露结算所需的最小数据，隔离 provider API 类型（实现集中在 infrastructure）。 */
public interface MinecraftPort {

    boolean minecraftAvailable();

    /** 已启用的服务器列表。 */
    List<ServerRef> servers();

    /** 指定服务器的玩家活动（累计口径），page 从 1 起。 */
    List<ActivityRef> playerActivities(String serverId, int page, int size);

    /** 指定服务器已知的子服拓扑（代理端上报）；非群组服或提供方不支持时为空列表。 */
    List<SubServerRef> subServers(String serverId);

    /**
     * 指定玩家在该服务器各子服上的累计时长明细。
     *
     * <p>没有子服维度时提供方可能只返回一条 {@code "default"} 记录（或空列表）；消费方按整服口径
     * 结算即可。提供方缺失、版本过旧或调用异常时一律返回空列表，绝不抛异常。
     */
    List<SubActivityRef> subServerActivities(String serverId, String playerId);

    record ServerRef(String id, String name) {
    }

    record ActivityRef(String serverId, String playerId, String playerName, boolean online,
                       long totalOnlineMillis, long totalAfkMillis, Long lastQuitAt) {
    }

    /** 一台下游子服：名称、是否默认入口、当前在线人数与是否装了传感器。 */
    record SubServerRef(String name, boolean defaultServer, int online, boolean sensor) {
    }

    /** 玩家在单个子服上的累计时长；{@code subServer} 为 {@code "default"} 表示没有子服维度。 */
    record SubActivityRef(String subServer, boolean online, long totalOnlineMillis, long totalAfkMillis) {
    }
}
