package online.yudream.base.plugin.playtimepoints.interfaces.assembler;

import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.interfaces.request.SaveSettingsRequest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaytimePointsSettingsAssembler {

    private PlaytimePointsSettingsAssembler() {
    }

    public static PlaytimePointsSettings toSettings(SaveSettingsRequest body) {
        Map<String, PlaytimePointsSettings.ServerRule> servers = new LinkedHashMap<>();
        if (body.servers() != null) {
            body.servers().forEach((serverId, rule) -> {
                if (serverId == null || serverId.isBlank() || rule == null) {
                    return;
                }
                servers.put(serverId.trim(), rule(rule));
            });
        }
        Map<String, PlaytimePointsSettings.ServerRule> subServers = new LinkedHashMap<>();
        if (body.subServers() != null) {
            body.subServers().forEach((key, rule) -> {
                String canonical = PlaytimePointsSettings.normalizeSubKey(key);
                if (canonical == null || rule == null) {
                    return;
                }
                subServers.put(canonical, rule(rule));
            });
        }
        Map<String, String> projectPoints = new LinkedHashMap<>();
        if (body.checkInRewardProjectPoints() != null) {
            body.checkInRewardProjectPoints().forEach((projectId, amount) -> {
                if (projectId == null || projectId.isBlank() || amount == null || amount.isBlank()) {
                    return;
                }
                projectPoints.put(projectId.trim(), amount.trim());
            });
        }
        return new PlaytimePointsSettings(
                body.enabled() == null || body.enabled(),
                body.assetCode() == null ? "" : body.assetCode().trim(),
                body.minutesPerPoint() == null ? 60 : body.minutesPerPoint(),
                body.subtractAfk() == null || body.subtractAfk(),
                servers,
                subServers,
                body.checkInRewardEnabled() != null && body.checkInRewardEnabled(),
                // 留空/缺省交给记录的紧凑构造回退默认金额 1（合法性与范围由应用服务校验）。
                body.checkInRewardPoints(),
                body.checkInRewardRealtime() != null && body.checkInRewardRealtime(),
                projectPoints,
                // 计算方式与时薪：缺省/未知由记录的紧凑构造回退 FIXED / 1，旧前端不带这两个字段也安全。
                body.checkInRewardMode(),
                body.checkInHourlyPoints(),
                // 非时长打卡积分：缺省/空由记录的紧凑构造回退 "0"（不发），旧前端不带该字段仍然安全。
                body.checkInFixedPoints());
    }

    private static PlaytimePointsSettings.ServerRule rule(SaveSettingsRequest.ServerRuleRequest rule) {
        String weight = rule.weight() == null ? null : BigDecimal.valueOf(rule.weight()).toPlainString();
        return new PlaytimePointsSettings.ServerRule(weight, rule.enabled() == null || rule.enabled());
    }
}
