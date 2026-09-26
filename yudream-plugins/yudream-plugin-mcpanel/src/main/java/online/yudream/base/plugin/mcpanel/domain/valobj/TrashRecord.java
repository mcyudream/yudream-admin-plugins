package online.yudream.base.plugin.mcpanel.domain.valobj;

import java.util.Map;

/**
 * 回收站记录：实例删除（未勾选永久删除）且节点确认数据目录已移入节点侧
 * data/trash/ 后落库的快照。trashId = 原实例 id；specSnapshot 保存完整实例
 * 聚合（找回时还原规格，端口/绑定/域名在恢复时重置，不随快照复用）。
 */
public record TrashRecord(
        String trashId,
        String nodeId,
        String instanceId,
        String instanceName,
        String kind,
        String mcVersion,
        String remark,
        String tenantId,
        Map<String, Object> specSnapshot,
        String deletedBy,
        long deletedAtMs) {

    public TrashRecord {
        // specSnapshot 允许含 null 值（如 lastExitCode），不能用 Map.copyOf（null 敌视）。
        specSnapshot = specSnapshot == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(specSnapshot));
        remark = remark == null ? "" : remark;
        mcVersion = mcVersion == null ? "" : mcVersion;
        tenantId = tenantId == null ? "" : tenantId;
    }

    public TrashRecord withSpecSnapshot(Map<String, Object> updated) {
        return new TrashRecord(trashId, nodeId, instanceId, instanceName, kind, mcVersion,
                remark, tenantId, updated, deletedBy, deletedAtMs);
    }
}
