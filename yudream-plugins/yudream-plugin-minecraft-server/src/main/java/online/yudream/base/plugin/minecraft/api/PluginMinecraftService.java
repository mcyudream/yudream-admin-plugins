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
}
