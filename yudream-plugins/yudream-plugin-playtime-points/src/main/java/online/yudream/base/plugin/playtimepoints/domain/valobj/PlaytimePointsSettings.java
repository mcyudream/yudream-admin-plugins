package online.yudream.base.plugin.playtimepoints.domain.valobj;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结算设置。servers 为按服务器 ID 的覆盖规则；未配置的服务器按默认规则参与结算（权重 1）。
 * weight 以字符串保存以保留十进制精度。
 */
public record PlaytimePointsSettings(
        boolean enabled,
        String assetCode,
        long minutesPerPoint,
        boolean subtractAfk,
        Map<String, ServerRule> servers) {

    public record ServerRule(String weight, boolean enabled) {

        /** 宽松解析：空/非法回退默认权重 1，负数按 0 处理（管理端保存时会先做严格校验）。 */
        public BigDecimal weightValue() {
            if (weight == null || weight.isBlank()) {
                return BigDecimal.ONE;
            }
            try {
                BigDecimal value = new BigDecimal(weight.trim());
                return value.signum() < 0 ? BigDecimal.ZERO : value;
            } catch (NumberFormatException e) {
                return BigDecimal.ONE;
            }
        }
    }

    public PlaytimePointsSettings {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }

    public static PlaytimePointsSettings defaults() {
        return new PlaytimePointsSettings(true, "POINT", 60, true, Map.of());
    }

    public ServerRule ruleFor(String serverId) {
        return serverId == null ? null : servers.get(serverId);
    }

    public BigDecimal weightFor(String serverId) {
        ServerRule rule = ruleFor(serverId);
        return rule == null ? BigDecimal.ONE : rule.weightValue();
    }

    public boolean enabledFor(String serverId) {
        ServerRule rule = ruleFor(serverId);
        return rule == null || rule.enabled();
    }

    /** 归一化：货币代码转大写、去掉空白服务器键。 */
    public PlaytimePointsSettings normalized() {
        String code = assetCode == null ? "" : assetCode.trim().toUpperCase();
        Map<String, ServerRule> normalized = new LinkedHashMap<>();
        servers.forEach((serverId, rule) -> {
            if (serverId != null && !serverId.isBlank() && rule != null) {
                normalized.put(serverId.trim(), rule);
            }
        });
        return new PlaytimePointsSettings(enabled, code, minutesPerPoint, subtractAfk, normalized);
    }
}
