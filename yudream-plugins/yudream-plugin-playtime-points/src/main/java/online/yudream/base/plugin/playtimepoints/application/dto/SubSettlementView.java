package online.yudream.base.plugin.playtimepoints.application.dto;

/**
 * 结算流水里的子服拆分行（REST 输出）。金额为十进制字符串；{@code enabled} 为 false 表示该子服
 * 当期不参与结算，points 恒为 0，但时长仍然如实展示。
 */
public record SubSettlementView(
        String subServer,
        long onlineMillis,
        long afkMillis,
        long effectiveMillis,
        long effectiveMinutes,
        String weight,
        String points,
        boolean enabled) {
}
