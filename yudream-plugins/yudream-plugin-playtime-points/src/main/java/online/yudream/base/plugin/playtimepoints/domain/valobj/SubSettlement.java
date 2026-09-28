package online.yudream.base.plugin.playtimepoints.domain.valobj;

/**
 * 一次退出结算里某个子服的拆分。
 *
 * <p>群组服下同一段会话的时长分散在多台子服上，每条子服按自己的权重折算积分；整服口径
 * （没有子服维度）不产生明细，读回旧流水文档时该明细为空列表。
 *
 * @param subServer       子服名（mc-server 上报；没有子服维度时不会出现在明细里）
 * @param onlineMillis    本段会话在该子服的在线时长
 * @param afkMillis       本段会话在该子服的挂机时长
 * @param effectiveMillis 本段会话在该子服的计分时长（按设置扣除挂机后）
 * @param weight          该子服的权重（十进制字符串，保留精度）
 * @param points          该子服折算出的积分（十进制字符串）
 * @param enabled         该子服是否参与结算；不参与时 points 恒为 0
 */
public record SubSettlement(
        String subServer,
        long onlineMillis,
        long afkMillis,
        long effectiveMillis,
        String weight,
        String points,
        boolean enabled) {

    public SubSettlement {
        subServer = subServer == null ? "" : subServer.trim();
        onlineMillis = Math.max(onlineMillis, 0L);
        afkMillis = Math.max(afkMillis, 0L);
        effectiveMillis = Math.max(effectiveMillis, 0L);
    }
}
