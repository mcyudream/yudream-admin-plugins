package online.yudream.base.plugin.mcpanel.interfaces.res;

import java.util.List;
import java.util.Map;

/**
 * 节点管理端响应（admin wrapped 信封内）。**永不包含 nodeSecret**；
 * hasSecret 仅表示凭据已生成；stats 结构见 StatsViews（协议 §5.2 + reportedAt）。
 */
public record NodeRes(
        String id,
        String name,
        String endpoint,
        String sftpHost,
        String tlsMode,
        String pinSha256,
        boolean localDevelopment,
        String remark,
        boolean enabled,
        String status,
        String tenantId,
        String agentVersion,
        String reportedHost,
        String sessionId,
        List<String> caps,
        String dockerVersion,
        String reportedCertSha256,
        boolean connected,
        boolean hasSecret,
        long lastSeenAt,
        long createdAt,
        long updatedAt,
        Integer portRangeStart,
        Integer portRangeEnd,
        Map<String, Object> stats) {
}
