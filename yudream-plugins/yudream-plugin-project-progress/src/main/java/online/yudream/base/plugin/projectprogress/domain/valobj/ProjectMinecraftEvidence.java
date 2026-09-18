package online.yudream.base.plugin.projectprogress.domain.valobj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次 Minecraft 在线时长打卡所留存的证据。
 *
 * <p>前三个 millis 字段是打卡**周期内**的窗口值，达标判断只看它们；{@code subServer} 记录这些
 * 窗口值是按哪台子服算的（空表示整服口径），{@code subServers} 是该玩家在各子服上的**累计**明细，
 * 只作附注：两种口径不同，不能相加，也不能互相替代。
 */
public record ProjectMinecraftEvidence(
        String serverId,
        String playerId,
        String playerName,
        String subServer,
        long totalOnlineMillis,
        long totalAfkMillis,
        long effectiveOnlineMillis,
        long periodStart,
        long periodEnd,
        List<ProjectMinecraftSubServerEvidence> subServers
) {

    public ProjectMinecraftEvidence {
        serverId = serverId == null ? "" : serverId.trim();
        playerId = playerId == null ? "" : playerId.trim();
        playerName = playerName == null ? "" : playerName.trim();
        subServer = subServer == null ? "" : subServer.trim();
        totalOnlineMillis = Math.max(totalOnlineMillis, 0);
        totalAfkMillis = Math.max(totalAfkMillis, 0);
        effectiveOnlineMillis = Math.max(effectiveOnlineMillis, 0);
        periodStart = Math.max(periodStart, 0);
        periodEnd = Math.max(periodEnd, periodStart);
        subServers = normalizeSubServers(subServers);
    }

    /**
     * 去掉空项并按累计在线时长从多到少排列。
     *
     * <p>排序放在这里而不是读取处：子服桶在 minecraft-server 侧按首次进入顺序落库，直接渲染会随
     * 加入次序跳动；同一此处排一次序，页面、导出与接口看到的就是同一个顺序。
     */
    private static List<ProjectMinecraftSubServerEvidence> normalizeSubServers(List<ProjectMinecraftSubServerEvidence> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<ProjectMinecraftSubServerEvidence> result = new ArrayList<>();
        for (ProjectMinecraftSubServerEvidence value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        result.sort(Comparator.comparingLong(ProjectMinecraftSubServerEvidence::onlineMillis).reversed()
                .thenComparing(ProjectMinecraftSubServerEvidence::name));
        return List.copyOf(result);
    }
}
