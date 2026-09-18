package online.yudream.base.plugin.minecraft.application.dto;

import java.util.List;

/**
 * 玩家活动记录的应用层视图。
 *
 * <p>顶层字段是跨子服的汇总（历史行为不变），{@code subServers} 是按子服拆开的明细，
 * 让界面能显示“A 服多久 + B 服多久”。没有子服维度时只有一条 {@code "default"} 明细。
 */
public record MinecraftPlayerActivityDTO(
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
        List<SubServerDTO> subServers
) {

    public MinecraftPlayerActivityDTO {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 一个子服上的时长明细；{@code name} 为 {@code "default"} 表示没有子服维度。 */
    public record SubServerDTO(
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
