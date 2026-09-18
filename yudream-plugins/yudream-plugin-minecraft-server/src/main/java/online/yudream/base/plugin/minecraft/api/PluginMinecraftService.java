package online.yudream.base.plugin.minecraft.api;

import java.util.List;
import java.util.Optional;

/** Stable service contract exposed by the minecraft-server plugin. */
public interface PluginMinecraftService {
    List<PluginMinecraftServer> minecraftServers(boolean includeDisabled);
    Optional<PluginMinecraftServer> minecraftServer(String serverId);
    List<PluginMinecraftPlayerActivity> minecraftPlayerActivities(String serverId, int page, int size);
    default Optional<PluginMinecraftOnlineWindow> minecraftOnlineWindow(String serverId, String playerId,
                                                                         long windowStart, long windowEnd) {
        return Optional.empty();
    }

    /** Players online on the server at any moment inside [windowStart, windowEnd], with their in-window durations. */
    default List<PluginMinecraftActivePlayer> minecraftActivePlayers(String serverId, long windowStart,
                                                                     long windowEnd) {
        return List.of();
    }

    /**
     * 玩家在各子服上的时长拆分（新增读取方法，不改变既有方法的签名与语义）。
     *
     * <p>群组服下同一玩家的时间会分散在多个子服上；{@link #minecraftPlayerActivities} 仍然返回
     * 跨子服的合计，本方法返回它的明细。没有子服维度时只有一条 {@code "default"} 记录。
     * 旧版本提供方混跑时自动降级为空列表。
     */
    default List<PluginMinecraftSubServerActivity> minecraftSubServerActivities(String serverId, String playerId) {
        return List.of();
    }

    /**
     * 该服务器已知的子服列表，来自代理端桥接上报的拓扑。
     *
     * <p>非代理端（单机服）返回空列表，管理端据此决定要不要显示子服选择。旧版本提供方混跑时
     * 自动降级为空列表，消费方按「没有子服维度」处理即可。
     */
    default List<PluginMinecraftSubServer> minecraftSubServers(String serverId) {
        return List.of();
    }

    /**
     * 按子服限定的时间窗统计；{@code subServer} 为空表示整服。
     *
     * <p>群组服下同一玩家的时间分散在多台子服上，整服口径会把它们加在一起。默认实现忽略子服
     * 参数并退回整服口径，这样旧版本提供方混跑时消费方不必写分支。
     */
    default Optional<PluginMinecraftOnlineWindow> minecraftOnlineWindow(String serverId, String playerId,
                                                                       String subServer, long windowStart,
                                                                       long windowEnd) {
        return minecraftOnlineWindow(serverId, playerId, windowStart, windowEnd);
    }

    /** 按子服限定的「窗口内上线过的玩家」；{@code subServer} 为空表示整服。 */
    default List<PluginMinecraftActivePlayer> minecraftActivePlayers(String serverId, String subServer,
                                                                    long windowStart, long windowEnd) {
        return minecraftActivePlayers(serverId, windowStart, windowEnd);
    }
}
