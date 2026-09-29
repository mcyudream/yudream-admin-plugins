package online.yudream.base.plugin.minecraft.domain.valobj;

/**
 * 一台 Minecraft 服务器的群服互联设置。
 *
 * <p>群服互联 = 游戏事件转发到 QQ 群 + 群聊消息转发进游戏。总开关 {@code enabled} 关闭时，
 * 无论细分开关如何配置都不转发；每个细分开关再独立控制一类消息。
 */
public record MinecraftBridgeSettings(
        String serverId,
        boolean enabled,
        String connectionId,
        String channelId,
        String channelName,
        boolean forwardChat,
        boolean forwardJoinQuit,
        boolean forwardDeath,
        boolean forwardAdvancement,
        boolean forwardStartStop,
        int forwardMergeSeconds,
        boolean forwardToGame,
        long updatedAt
) {

    /** 未配置时的默认值：整体关闭、全部细分关闭。 */
    public static MinecraftBridgeSettings empty(String serverId) {
        return new MinecraftBridgeSettings(serverId, false, "", "", "",
                false, false, false, false, false, 8, false, 0L);
    }

    /** 转发目标已选定（启用了群服互联就必须同时配置连接与群聊）。 */
    public boolean targetConfigured() {
        return connectionId != null && !connectionId.isBlank()
                && channelId != null && !channelId.isBlank();
    }

    /** 保存前归一：trim 文本、布尔与时间戳兜底。 */
    public MinecraftBridgeSettings normalize(long at) {
        return new MinecraftBridgeSettings(
                serverId,
                enabled,
                text(connectionId),
                text(channelId),
                text(channelName),
                forwardChat,
                forwardJoinQuit,
                forwardDeath,
                forwardAdvancement,
                forwardStartStop,
                normalizeMergeSeconds(forwardMergeSeconds),
                forwardToGame,
                at <= 0 ? System.currentTimeMillis() : at
        );
    }

    /** 合并窗口秒数：0-60，超出按边界处理（0=每条聊天即时发送）。 */
    private static int normalizeMergeSeconds(int value) {
        return Math.max(0, Math.min(60, value));
    }

    /** 启用时目标必须完整。 */
    public void validate() {
        if (enabled && !targetConfigured()) {
            throw new IllegalArgumentException("启用群服互联前必须选择消息连接与群聊");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
