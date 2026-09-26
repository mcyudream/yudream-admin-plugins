package online.yudream.base.plugin.mcpanel.application.dto;

import java.util.Map;

/**
 * bootstrap 成功响应（机器面 rawJson 下发）。nodeSecret 仅此处出现一次。
 */
public record EnrollResultDTO(
        String nodeId,
        String nodeSecret,
        long serverTimeMs,
        Map<String, Object> controlPlan) {

    public static EnrollResultDTO direct(String nodeId, String nodeSecret, long serverTimeMs) {
        return new EnrollResultDTO(nodeId, nodeSecret, serverTimeMs, Map.of("mode", "direct"));
    }
}
