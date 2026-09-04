package online.yudream.base.plugin.minecraft.api;

/**
 * A player that was online on a server at any moment inside [windowStart, windowEnd].
 * firstJoinAt is the first JOIN event inside the window, or 0 when the session started before the window.
 */
public record PluginMinecraftActivePlayer(String serverId, String playerId, String playerName, long firstJoinAt,
                                          long onlineMillis, long afkMillis, long effectiveOnlineMillis) {
}
