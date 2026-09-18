package online.yudream.base.plugin.minecraft.application.cmd;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;

import java.util.List;

/**
 * 一次在线玩家快照上报，两种形态：
 *
 * <ul>
 *   <li><b>扁平</b>：只带 {@code players}，表示“没有子服维度”，语义与本次改动前完全一致——
 *       快照覆盖整台服务器，名册里没有的在线玩家会被关闭。</li>
 *   <li><b>分组</b>：带 {@code servers}，每个子服一份名册。列出的子服即使 {@code players} 为空
 *       也有意义：它表示“该子服上现在没人”，Admin 只关闭这些子服上的人，不会误伤其它子服。</li>
 * </ul>
 */
public record MinecraftPlayerSnapshotCmd(
        Long observedAt,
        List<Player> players,
        List<Server> servers
) {

    public MinecraftPlayerSnapshotCmd {
        players = players == null ? List.of() : List.copyOf(players);
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /** 兼容旧调用方的扁平形态。 */
    public MinecraftPlayerSnapshotCmd(Long observedAt, List<Player> players) {
        this(observedAt, players, List.of());
    }

    /** 是否携带子服维度。没有列出任何子服时按扁平形态处理。 */
    public boolean grouped() {
        return !servers.isEmpty();
    }

    public record Player(String playerId, String playerName) {
    }

    /** 一个子服的名册；空名册表示该子服上当前没有玩家。 */
    public record Server(String name, List<Player> players) {
        public Server {
            players = players == null ? List.of() : List.copyOf(players);
        }

        /** 归一后的子服名：空白一律变成默认桶名。 */
        public String subServer() {
            return MinecraftSubServerActivity.normalizeName(name);
        }
    }
}
