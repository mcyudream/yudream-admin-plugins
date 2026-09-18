package online.yudream.base.plugin.minecraft.interfaces.request;

/**
 * 玩家事件上报请求。
 *
 * <p>{@code server} 是新增的可选子服名（代理下的 {@code /server} 名）。旧版上报端不发送该字段，
 * 解析后为 {@code null}，与改动前完全一致地归入默认桶。
 */
public record MinecraftPlayerEventRequest(
        String playerId,
        String uuid,
        String playerName,
        String name,
        Long eventAt,
        String server
) {
}
