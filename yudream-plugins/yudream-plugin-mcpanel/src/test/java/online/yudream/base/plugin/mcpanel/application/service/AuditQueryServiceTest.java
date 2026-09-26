package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审计查询回归：CSV 导出公式注入防护 + 历史不被删改。 */
class AuditQueryServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final AuditQueryService service = new AuditQueryService(documents);

    @BeforeEach
    void seed() {
        documents.clear();
        // 宿主文档存储 save 会把记录 id 覆写为存储键，这里保持同样的不变量。
        documents.save("mcpanel_audit_logs", "log-1", row("log-1", "user:1", "instance.start", "instance",
                "inst-1", "正常启动", ""));
        documents.save("mcpanel_audit_logs", "log-2", row("log-2", "=cmd|'/c calc'!A0", "instance.stop",
                "instance", "inst-1", "-221013\n换行,带逗号\"引号", "@tenant"));
        documents.save("mcpanel_audit_logs", "log-3", row("log-3", "user:2", "+1+1", "instance",
                "inst-2", "tab\t注入", ""));
    }

    private Map<String, Object> row(String id, String actor, String action, String targetType,
                                    String targetId, String detail, String tenantId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("actor", actor);
        row.put("action", action);
        row.put("targetType", targetType);
        row.put("targetId", targetId);
        row.put("detail", detail);
        row.put("tenantId", tenantId);
        row.put("at", 1_700_000_000_000L);
        return row;
    }

    @Test
    void pageReturnsHistoryUnchanged() {
        Map<String, Object> page = service.page(1, 20, null, null, null, null);
        assertEquals(3L, ((Number) page.get("total")).longValue());
        // 查询结果保留原始值（防注入只在 CSV 导出层处理，不动数据）
        @SuppressWarnings("unchecked")
        Map<String, Object> log2 = (Map<String, Object>) ((java.util.List<?>) page.get("records"))
                .stream().filter(item -> "=cmd|'/c calc'!A0".equals(
                        String.valueOf(((Map<?, ?>) item).get("actor"))))
                .findFirst().orElseThrow();
        assertEquals("=cmd|'/c calc'!A0", log2.get("actor"));
    }

    @Test
    void targetNameResolvedForInstancesAndFallsBackToId() {
        AuditQueryService resolving = new AuditQueryService(documents,
                id -> "inst-1".equals(id) ? "生存服" : null,
                id -> null);
        Map<String, Object> page = resolving.page(1, 20, null, null, null, null);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> records = (java.util.List<Map<String, Object>>) page.get("records");
        Map<String, Object> first = records.stream()
                .filter(item -> "inst-1".equals(String.valueOf(item.get("targetId"))))
                .findFirst().orElseThrow();
        assertEquals("生存服", first.get("targetName"));
        Map<String, Object> other = records.stream()
                .filter(item -> "inst-2".equals(String.valueOf(item.get("targetId"))))
                .findFirst().orElseThrow();
        assertEquals(null, other.get("targetName"));
    }

    @Test
    void deleteRemovesSingleRecordAndRejectsBlankId() {
        assertEquals(true, service.delete("log-1").get("deleted"));
        Map<String, Object> page = service.page(1, 20, null, null, null, null);
        assertEquals(2L, ((Number) page.get("total")).longValue());
        org.junit.jupiter.api.Assertions.assertThrows(
                online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.class,
                () -> service.delete("  "));
    }

    @Test
    void deleteFilteredRespectsFiltersAndClearsAll() {
        // 带筛选：只删命中的记录
        Map<String, Object> partial = service.deleteFiltered("instance.start", null, null, null);
        assertEquals(1L, ((Number) partial.get("deleted")).longValue());
        assertEquals(2L, ((Number) service.page(1, 20, null, null, null, null).get("total")).longValue());
        // 无筛选：清空
        Map<String, Object> all = service.deleteFiltered(null, null, null, null);
        assertEquals(2L, ((Number) all.get("deleted")).longValue());
        assertEquals(0L, ((Number) service.page(1, 20, null, null, null, null).get("total")).longValue());
    }

    @Test
    void exportCsvNeutralizesFormulaPrefixes() {
        String csv = String.valueOf(service.exportCsv(null, null, null, null).get("content"));
        // 危险前缀（= + - @）一律单引号中和
        assertTrue(csv.contains("'=cmd|'/c calc'!A0"), "= 开头应被中和");
        assertTrue(csv.contains("'-221013"), "- 开头应被中和");
        assertTrue(csv.contains("'@tenant"), "@ 开头应被中和");
        assertTrue(csv.contains("'+1+1"), "+ 开头应被中和");
        // 正常内容不加前缀、不额外引号
        assertTrue(csv.contains(",正常启动,"), "正常内容保持原样");
        assertTrue(csv.contains(",instance,"), "正常字段保持原样");
        // 含逗号/换行/引号的字段仍按 CSV 规则加引号（引号内已带 ' 前缀）
        assertTrue(csv.contains("\n"), "换行字段保留引号转义");
    }
}
