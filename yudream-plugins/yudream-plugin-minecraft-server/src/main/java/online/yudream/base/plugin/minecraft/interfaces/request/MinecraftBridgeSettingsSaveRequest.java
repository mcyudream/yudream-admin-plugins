package online.yudream.base.plugin.minecraft.interfaces.request;

/**
 * 群服互联设置保存请求。
 *
 * <p>{@code enabled} 是总开关；各 forward 开关独立控制一类消息。启用前必须已选择
 * {@code connectionId} 与 {@code channelId}（消息连接与群聊）。
 */
public record MinecraftBridgeSettingsSaveRequest(
        Boolean enabled,
        String connectionId,
        String channelId,
        String channelName,
        Boolean forwardChat,
        Boolean forwardJoinQuit,
        Boolean forwardDeath,
        Boolean forwardAdvancement,
        Boolean forwardToGame
) {
}
