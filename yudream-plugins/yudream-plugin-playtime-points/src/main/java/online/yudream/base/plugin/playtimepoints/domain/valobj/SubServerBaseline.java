package online.yudream.base.plugin.playtimepoints.domain.valobj;

/**
 * 玩家在<b>某一个子服</b>上的已结算累计基线。
 *
 * <p>与 {@code SettlementState} 的整服基线并存：整服基线在两种口径下都要推进，子服基线只在
 * mc-server 提供子服明细时用于按子服求差值。改造之前写入的基线没有子服表，读出来是空表，
 * 此时按整服口径结算，行为与改造前完全一致。
 *
 * @param name         子服名
 * @param onlineMillis 已结算的累计在线毫秒
 * @param afkMillis    已结算的累计挂机毫秒
 */
public record SubServerBaseline(String name, long onlineMillis, long afkMillis) {

    public SubServerBaseline {
        name = name == null ? "" : name.trim();
        onlineMillis = Math.max(onlineMillis, 0L);
        afkMillis = Math.max(afkMillis, 0L);
    }
}
