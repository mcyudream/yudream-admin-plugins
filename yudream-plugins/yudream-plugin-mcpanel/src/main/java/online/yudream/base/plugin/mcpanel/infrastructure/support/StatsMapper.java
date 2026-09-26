package online.yudream.base.plugin.mcpanel.infrastructure.support;

import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * wire stats（协议 §5.2 冻结 schema）→ 面板快照。
 * 冻结字段：cpuPercent / memUsedMb / memTotalMb / diskUsedGb / diskTotalGb /
 * load1（可省略或 null）/ containers[{instanceId,state,cpuPercent,memUsedMb}] /
 * dockerVersion / agentVersion。未知字段忽略（向前兼容）。
 */
public final class StatsMapper {

    private StatsMapper() {
    }

    public static NodeStatsSnapshot fromWire(Map<String, Object> payload, long nowMs, String fallbackAgentVersion) {
        if (payload == null) {
            payload = Map.of();
        }
        List<NodeStatsSnapshot.ContainerStat> containers = new ArrayList<>();
        if (payload.get("containers") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    containers.add(new NodeStatsSnapshot.ContainerStat(
                            text(map.get("instanceId")),
                            text(map.get("state")),
                            number(map.get("cpuPercent")),
                            longValue(map.get("memUsedMb"))));
                }
            }
        }
        return new NodeStatsSnapshot(
                number(payload.get("cpuPercent")),
                longValue(payload.get("memUsedMb")),
                longValue(payload.get("memTotalMb")),
                longValue(payload.get("diskUsedGb")),
                longValue(payload.get("diskTotalGb")),
                nullableDouble(payload.get("load1")),
                containers,
                text(payload.get("dockerVersion")),
                orFallback(text(payload.get("agentVersion")), fallbackAgentVersion),
                nowMs);
    }

    private static String orFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Double nullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double number(Object value) {
        Double parsed = nullableDouble(value);
        return parsed == null ? 0d : parsed;
    }

    private static long longValue(Object value) {
        Double parsed = nullableDouble(value);
        return parsed == null ? 0L : Math.round(parsed);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
