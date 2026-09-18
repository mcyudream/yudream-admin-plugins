package online.yudream.base.plugin.projectprogress.domain.valobj;

/**
 * 项目的 Minecraft 在线时长打卡策略。
 *
 * <p>{@code subServer} 只在选中的服务器是群组服代理时有意义：空表示整服口径（把该玩家在这台服
 * 全部子服上的时长相加），否则只按那一台下游子服计时长。两者不可混同——群组服下玩家的时间分散在
 * 多台子服上，整服口径会把它们加在一起。
 */
public record ProjectMinecraftPolicy(
        boolean enabled,
        String serverId,
        String subServer,
        int requiredOnlineMinutes,
        boolean includeAfk,
        boolean autoCheckInEnabled
) {

    public static ProjectMinecraftPolicy disabled() {
        return new ProjectMinecraftPolicy(false, null, null, 0, false, false);
    }

    public ProjectMinecraftPolicy {
        serverId = serverId == null || serverId.isBlank() ? null : serverId.trim();
        subServer = subServer == null || subServer.isBlank() ? null : subServer.trim();
        requiredOnlineMinutes = Math.max(requiredOnlineMinutes, 0);
        if (enabled && (serverId == null || serverId.isBlank())) {
            throw new IllegalArgumentException("启用 Minecraft 打卡时必须选择服务器");
        }
        if (enabled && requiredOnlineMinutes <= 0) {
            throw new IllegalArgumentException("Minecraft 在线时长阈值必须大于 0 分钟");
        }
        if (!enabled) {
            // 未启用时不留残留字段，避免禁用再启用时带着上一个服务器的子服。
            subServer = null;
        }
    }

    /** 是否只按某一台子服计时长；false 表示整服口径。 */
    public boolean scopedToSubServer() {
        return enabled && subServer != null && !subServer.isEmpty();
    }
}
