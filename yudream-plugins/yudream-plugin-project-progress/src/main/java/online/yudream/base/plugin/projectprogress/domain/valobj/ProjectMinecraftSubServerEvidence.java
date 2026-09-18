package online.yudream.base.plugin.projectprogress.domain.valobj;

/**
 * 玩家在某一台子服上的时长明细，作为打卡证据的附注。
 *
 * <p>这是**累计**值：该玩家在这台子服上的全部历史时长，与本次打卡周期无关。判定是否达标只用
 * 周期内的窗口值（{@link ProjectMinecraftEvidence} 里的三个 millis 字段），这份明细回答的是
 * 「这些时间分布在哪儿」，不参与达标判断，也不会因为明细缺失而让打卡失败。
 *
 * <p>{@code name} 为空表示这条数据没有子服维度——单机服，或按子服上报之前的旧记录。
 */
public record ProjectMinecraftSubServerEvidence(
        String name,
        long onlineMillis,
        long afkMillis
) {

    public ProjectMinecraftSubServerEvidence {
        name = name == null ? "" : name.trim();
        onlineMillis = Math.max(onlineMillis, 0);
        afkMillis = Math.max(afkMillis, 0);
    }
}
