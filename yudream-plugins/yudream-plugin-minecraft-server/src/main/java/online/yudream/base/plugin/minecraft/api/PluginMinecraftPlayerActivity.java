package online.yudream.base.plugin.minecraft.api;

public record PluginMinecraftPlayerActivity(String serverId, String playerId, String playerName, boolean online,
                                            boolean afk, long totalOnlineMillis, long totalAfkMillis,
                                            Long currentOnlineSince, Long currentAfkSince, Long lastJoinedAt,
                                            Long lastQuitAt, long updatedAt) {
}
