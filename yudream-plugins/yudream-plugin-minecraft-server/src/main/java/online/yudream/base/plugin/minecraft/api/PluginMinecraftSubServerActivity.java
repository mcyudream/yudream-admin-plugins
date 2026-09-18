package online.yudream.base.plugin.minecraft.api;

/**
 * 玩家在某个子服上的时长拆分，供其它插件做按子服统计。
 *
 * <p>{@code subServer} 为 {@code "default"} 时表示该记录没有子服维度（独立服或旧版上报）。
 */
public record PluginMinecraftSubServerActivity(String serverId, String playerId, String subServer,
                                               boolean online, boolean afk, long totalOnlineMillis,
                                               long totalAfkMillis, Long currentOnlineSince,
                                               Long currentAfkSince, Long lastJoinedAt, Long lastQuitAt) {
}
