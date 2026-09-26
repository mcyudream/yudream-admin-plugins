package online.yudream.base.plugin.minecraft.application.cmd;

/**
 * 一次游戏内事件上报（聊天、死亡、成就）。
 *
 * <p>与 {@link MinecraftPlayerEventCmd} 分开：这类事件不参与玩家活动统计，
 * 只用于群服互联转发，额外携带 {@code content} 文本。
 *
 * @param server 子服名，可空
 */
public record MinecraftGameEventCmd(
        String playerId,
        String playerName,
        Long eventAt,
        String server,
        String content
) {
}
