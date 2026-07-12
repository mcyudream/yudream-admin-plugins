package online.yudream.base.plugin.minecraft.api;

public record PluginMinecraftOnlineWindow(String serverId, String playerId, String playerName, long windowStart,
                                          long windowEnd, long onlineMillis, long afkMillis,
                                          long effectiveOnlineMillis) {
}
