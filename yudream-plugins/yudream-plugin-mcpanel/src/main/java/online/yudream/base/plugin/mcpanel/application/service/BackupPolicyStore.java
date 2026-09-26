package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例备份保留策略（按实例）：keepCount=最多保留份数、keepDays=最长保留天数，
 * 0=不限。节点在每次 backup.create 成功后按策略清理旧档；保存策略时面板
 * 立即触发一次 backup.prune。落在面板文档存储（mcpanel_backup_policies），
 * 不进实例聚合（策略是备份域配置，与实例规格无关）。
 */
public class BackupPolicyStore {

    private static final String COLLECTION = "mcpanel_backup_policies";
    private static final int MAX_KEEP_COUNT = 999;
    private static final int MAX_KEEP_DAYS = 3650;

    private final PluginDocumentStore documents;

    public BackupPolicyStore(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /** 保留策略值对象（0=不限）。 */
    public record Policy(int keepCount, int keepDays) {
    }

    /** 读取实例策略；未配置返回不限（0/0）。 */
    public Policy get(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return new Policy(0, 0);
        }
        return documents.findById(COLLECTION, instanceId)
                .map(record -> new Policy(
                        (int) longValue(record.get("keepCount")),
                        (int) longValue(record.get("keepDays"))))
                .orElse(new Policy(0, 0));
    }

    /** 保存实例策略并返回规范化后的值。 */
    public Policy save(String instanceId, int keepCount, int keepDays) {
        if (instanceId == null || instanceId.isBlank()) {
            throw McpanelBusinessException.invalid("缺少实例 ID");
        }
        if (keepCount < 0 || keepCount > MAX_KEEP_COUNT) {
            throw McpanelBusinessException.invalid("保留份数需在 0-" + MAX_KEEP_COUNT + " 之间（0=不限）");
        }
        if (keepDays < 0 || keepDays > MAX_KEEP_DAYS) {
            throw McpanelBusinessException.invalid("保留天数需在 0-" + MAX_KEEP_DAYS + " 之间（0=不限）");
        }
        if (keepCount == 0 && keepDays == 0) {
            documents.delete(COLLECTION, instanceId);
            return new Policy(0, 0);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("instanceId", instanceId);
        record.put("keepCount", keepCount);
        record.put("keepDays", keepDays);
        record.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, instanceId, record);
        return new Policy(keepCount, keepDays);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }
}
