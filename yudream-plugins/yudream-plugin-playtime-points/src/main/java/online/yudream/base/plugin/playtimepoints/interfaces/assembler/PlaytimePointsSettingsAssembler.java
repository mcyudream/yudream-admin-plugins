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
                String weight = rule.weight() == null ? null : BigDecimal.valueOf(rule.weight()).toPlainString();
                servers.put(serverId.trim(), new PlaytimePointsSettings.ServerRule(weight, rule.enabled() == null || rule.enabled()));
            });
        }
        return new PlaytimePointsSettings(
                body.enabled() == null || body.enabled(),
                body.assetCode() == null ? "" : body.assetCode().trim(),
                body.minutesPerPoint() == null ? 60 : body.minutesPerPoint(),
                body.subtractAfk() == null || body.subtractAfk(),
                servers);
    }
}
