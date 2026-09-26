package online.yudream.base.plugin.minecraft.interfaces.res;

/**
 * 群服互联设置视图。
 *
 * <p>{@code configured=false} 表示尚未选择消息连接与群聊，此时无法启用。
 */
public record MinecraftBridgeSettingsRes(
        String serverId,
        boolean enabled,
        String connectionId,
        String channelId,
        String channelName,
        boolean forwardChat,
        boolean forwardJoinQuit,
        boolean forwardDeath,
        boolean forwardAdvancement,
        boolean forwardToGame,
        boolean configured,
        long updatedAt
) {
}
