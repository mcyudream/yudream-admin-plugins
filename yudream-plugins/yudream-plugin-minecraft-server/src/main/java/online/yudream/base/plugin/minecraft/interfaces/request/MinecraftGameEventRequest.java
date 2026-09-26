package online.yudream.base.plugin.minecraft.interfaces.request;

/**
 * 游戏内事件上报请求（聊天、死亡、成就）。
 *
 * <p>{@code content} 为事件文本：聊天原文、死亡消息或成就名称。
 */
public record MinecraftGameEventRequest(
        String playerId,
        String uuid,
        String playerName,
        String name,
        Long eventAt,
        String server,
        String content
) {
}
