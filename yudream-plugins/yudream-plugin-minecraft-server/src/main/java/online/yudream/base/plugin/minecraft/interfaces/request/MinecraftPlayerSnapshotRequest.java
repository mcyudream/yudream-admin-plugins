package online.yudream.base.plugin.minecraft.interfaces.request;

import java.util.List;

/**
 * 在线玩家快照上报请求，支持两种形态：
 *
 * <ul>
 *   <li>旧版扁平形态：只带 {@code players}，表示没有子服维度。</li>
 *   <li>新版分组形态：带 {@code servers}，每个子服一份名册；名册为空的子服同样有意义。</li>
 * </ul>
 */
public record MinecraftPlayerSnapshotRequest(
        Long observedAt,
        List<Player> players,
        List<Server> servers
) {
    public record Player(String playerId, String uuid, String playerName, String name) {
    }

    public record Server(String name, List<Player> players) {
    }
}
