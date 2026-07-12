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
}
