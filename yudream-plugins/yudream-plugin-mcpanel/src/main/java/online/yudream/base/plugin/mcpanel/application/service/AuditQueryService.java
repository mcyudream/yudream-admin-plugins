package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板操作审计（对标 MCSM AuditLog）：
 * 查询 + 导出（CSV 文本），字段覆盖 actor/action/target/tenant/detail/at。
 */
public class AuditQueryService {

    private static final String COLLECTION = "mcpanel_audit_logs";

    private final PluginDocumentStore documents;
    /** 对象 ID → 可读名称（实例/节点）；记录时点之后的改名/删除以查询时为准，解析不到回退 ID。 */
    private final java.util.function.Function<String, String> instanceNameResolver;
    private final java.util.function.Function<String, String> nodeNameResolver;

    public AuditQueryService(PluginDocumentStore documents) {
        this(documents, null, null);
    }

    public AuditQueryService(PluginDocumentStore documents,
                             java.util.function.Function<String, String> instanceNameResolver,
                             java.util.function.Function<String, String> nodeNameResolver) {
        this.documents = documents;
        this.instanceNameResolver = instanceNameResolver;
        this.nodeNameResolver = nodeNameResolver;
    }

    private String resolveTargetName(Object targetType, Object targetId) {
        String id = targetId == null ? "" : String.valueOf(targetId);
        if (id.isEmpty()) {
            return null;
        }
        try {
            if ("instance".equals(targetType) && instanceNameResolver != null) {
                return instanceNameResolver.apply(id);
            }
            if ("node".equals(targetType) && nodeNameResolver != null) {
                return nodeNameResolver.apply(id);
            }
        } catch (RuntimeException ignored) {
            // 名称解析失败不影响审计查询本身
        }
        return null;
    }

    public Map<String, Object> page(int page, int size, String action, String actor,
                                    String targetType, String targetId) {
        List<Map<String, Object>> filtered = filter(action, actor, targetType, targetId);
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 20 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("fields", List.of("logId", "at", "actor", "action", "targetType", "targetId", "detail", "tenantId"));
        return result;
    }

    /** 删除单条审计记录（管理端危险操作，由 DELETE_PERMISSION 端点保护）。 */
    public Map<String, Object> delete(String logId) {
        String id = logId == null ? "" : logId.trim();
        if (id.isEmpty()) {
            throw online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.invalid("缺少审计日志 ID");
        }
        documents.delete(COLLECTION, id);
        return Map.of("deleted", true);
    }

    /**
     * 一键清理：删除当前筛选条件下的全部记录（无筛选 = 清空）。
     * 危险操作，由 DELETE_PERMISSION 端点保护；返回实际删除条数供确认反馈。
     */
    public Map<String, Object> deleteFiltered(String action, String actor, String targetType, String targetId) {
        List<Map<String, Object>> filtered = filter(action, actor, targetType, targetId);
        int deleted = 0;
        for (Map<String, Object> item : filtered) {
            String id = String.valueOf(item.get("id"));
            if (id.isEmpty() || "null".equals(id)) {
                continue;
            }
            documents.delete(COLLECTION, id);
            deleted++;
        }
        return Map.of("deleted", deleted);
    }

    /** 导出当前筛选结果为 CSV 文本（含表头，UTF-8 BOM 便于 Excel）。 */
    public Map<String, Object> exportCsv(String action, String actor, String targetType, String targetId) {
        List<Map<String, Object>> filtered = filter(action, actor, targetType, targetId);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("logId,at,actor,action,targetType,targetId,detail,tenantId\n");
        for (Map<String, Object> item : filtered) {
            csv.append(csv(item.get("id"))).append(',')
                    .append(csv(formatAt(item.get("at")))).append(',')
                    .append(csv(item.get("actor"))).append(',')
                    .append(csv(item.get("action"))).append(',')
                    .append(csv(item.get("targetType"))).append(',')
                    .append(csv(item.get("targetId"))).append(',')
                    .append(csv(item.get("detail"))).append(',')
                    .append(csv(item.get("tenantId")))
                    .append('\n');
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", "mcpanel-audit-" + System.currentTimeMillis() + ".csv");
        result.put("total", filtered.size());
        result.put("content", csv.toString());
        return result;
    }

    private List<Map<String, Object>> filter(String action, String actor, String targetType, String targetId) {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, 200);
            all.addAll(batch);
            p++;
        } while (batch.size() == 200);
        String actionKw = lower(action);
        String actorKw = lower(actor);
        String typeKw = lower(targetType);
        String idKw = lower(targetId);
        return all.stream()
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>(item);
                    row.putIfAbsent("id", item.get("id"));
                    row.putIfAbsent("logId", item.get("id"));
                    String targetName = resolveTargetName(item.get("targetType"), item.get("targetId"));
                    if (targetName != null && !targetName.isBlank()) {
                        row.put("targetName", targetName);
                    }
                    return row;
                })
                .filter(item -> actionKw.isEmpty()
                        || lower(item.get("action")).contains(actionKw))
                .filter(item -> actorKw.isEmpty()
                        || lower(item.get("actor")).contains(actorKw))
                .filter(item -> typeKw.isEmpty()
                        || lower(item.get("targetType")).contains(typeKw))
                .filter(item -> idKw.isEmpty()
                        || lower(item.get("targetId")).contains(idKw))
                .sorted((a, b) -> Long.compare(
                        toLong(b.get("at")), toLong(a.get("at"))))
                .toList();
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase();
    }

    private static String formatAt(Object value) {
        long ms = toLong(value);
        if (ms <= 0) {
            return "";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(ms));
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        // 公式注入防护：= + - @ 及制表/回车开头的单元格前置单引号，阻断 Excel/LibreOffice 求值。
        if (!text.isEmpty() && FORMULA_PREFIX.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static final String FORMULA_PREFIX = "=+-@\t\r";
}
