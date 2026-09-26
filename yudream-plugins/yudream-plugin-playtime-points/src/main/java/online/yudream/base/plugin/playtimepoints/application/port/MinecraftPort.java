package online.yudream.base.plugin.playtimepoints.application.port;

import java.util.List;

/** minecraft-server 插件端口：只暴露结算所需的最小数据，隔离 provider API 类型（实现集中在 infrastructure）。 */
public interface MinecraftPort {

    boolean minecraftAvailable();

    /** 已启用的服务器列表。 */
    List<ServerRef> servers();

    /** 指定服务器的玩家活动（累计口径），page 从 1 起。 */
    List<ActivityRef> playerActivities(String serverId, int page, int size);

    record ServerRef(String id, String name) {
    }

    record ActivityRef(String serverId, String playerId, String playerName, boolean online,
                       long totalOnlineMillis, long totalAfkMillis, Long lastQuitAt) {
    }
}
