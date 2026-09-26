package online.yudream.base.plugin.playtimepoints.interfaces.request;

import java.util.Map;

/**
 * PUT /admin/settings 请求体。servers 键为服务器 ID，subServers 键为 {@code serverId::subServer}；
 * weight 用 Double 接收 JSON 数字，保存前转十进制字符串。
 *
 * <p>打卡积分联动字段：{@code checkInRewardPoints}、{@code checkInHourlyPoints}、
 * {@code checkInFixedPoints} 与 {@code checkInRewardProjectPoints} 的值用<b>字符串</b>接收十进制金额，
 * 避免经过 Double 丢精度（与 weight 的取舍不同，金额必须逐位保真）。{@code checkInRewardMode} 为
 * {@code FIXED}（每次固定积分，默认）或 {@code HOURLY}（按时薪折算）；{@code checkInRewardProjectPoints}
 * 的语义随模式变化：固定模式是每次金额，时薪模式是每小时积分，且只作用于主口径。
 * {@code checkInFixedPoints} 是「没有时长的打卡」（图片/文件/定位等非 MC 打卡）在按时薪模式下每次发放的
 * 积分数，{@code 0} 表示不发；它是全局值，不参与项目覆盖，固定模式下不生效。</p>
 */
public record SaveSettingsRequest(
        Boolean enabled,
        String assetCode,
        Long minutesPerPoint,
        Boolean subtractAfk,
        Map<String, ServerRuleRequest> servers,
        Map<String, ServerRuleRequest> subServers,
        Boolean checkInRewardEnabled,
        String checkInRewardPoints,
        Boolean checkInRewardRealtime,
        Map<String, String> checkInRewardProjectPoints,
        String checkInRewardMode,
        String checkInHourlyPoints,
        String checkInFixedPoints) {

    public record ServerRuleRequest(Double weight, Boolean enabled) {
    }
}
