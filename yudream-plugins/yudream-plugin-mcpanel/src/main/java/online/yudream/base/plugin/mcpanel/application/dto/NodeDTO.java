package online.yudream.base.plugin.mcpanel.application.dto;

import java.util.List;
import java.util.Map;

/**
 * 节点管理端视图。统计为 wire 归一化后的 Map（结构见 StatsMapper），
 * 绝不包含 nodeSecret；hasSecret 仅表示是否已完成 bootstrap。
 *
 * @param history 后台采集的整机曲线（30s 采样、已落库）：{at, cpuPercent, memUsedMb, memTotalMb}
 * @param accessMode 玩家接入方式：direct/manual/frp/p2p（见 NodeAccess）
 * @param accessHost manual/frp 时的解析地址（IP 或主机名）
 * @param accessLabel 接入方式中文名（界面直接展示）
 */
public record NodeDTO(
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
        Map<String, Object> stats,
        List<Map<String, Object>> history,
        String accessMode,
        String accessHost,
        String accessLabel) {
}
