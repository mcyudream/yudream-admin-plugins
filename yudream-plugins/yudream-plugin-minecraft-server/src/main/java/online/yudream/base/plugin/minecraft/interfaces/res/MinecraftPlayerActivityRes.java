package online.yudream.base.plugin.minecraft.interfaces.res;

import java.util.List;

/**
 * 玩家活动记录的接口返回。
 *
 * <p>顶层字段保持既有契约不变；{@code subServers} 是新增的按子服拆分，供前端展示每个子服的时长。
 */
public record MinecraftPlayerActivityRes(
        String serverId,
        String playerId,
        String playerName,
        boolean online,
        boolean afk,
        long totalOnlineMillis,
        long totalAfkMillis,
        Long currentOnlineSince,
        Long currentAfkSince,
        Long lastJoinedAt,
        Long lastQuitAt,
        long updatedAt,
        List<SubServerRes> subServers
) {

    public MinecraftPlayerActivityRes {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 一个子服上的时长明细；{@code name} 为 {@code "default"} 表示没有子服维度。 */
    public record SubServerRes(
            String name,
            boolean online,
            boolean afk,
            long onlineMillis,
            long afkMillis,
            Long currentOnlineSince,
            Long currentAfkSince,
            Long lastJoinedAt,
            Long lastQuitAt
    ) {
    }
}
