package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 节点贡献（M8 玩家面）：/me/contributions 自助申请 + 管理端审核。
 * 数据范围铁律：玩家只能看/撤回自己的申请；审核与限额在 /admin。
 */
public class ContributionService {

    private static final String COLLECTION = "mcpanel_contributions";
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;
    private final SettingsService settings;

    public ContributionService(PluginDocumentStore documents, ObjectMapper mapper, SettingsService settings) {
        this.documents = documents;
        this.mapper = mapper;
        this.settings = settings;
    }

    public Map<String, Object> submit(long userId, String machineSpec, String bandwidth, String onlineHours, String note) {
        var contribution = settings.load().contribution();
        if (contribution == null || !contribution.enabled()) {
            throw new McpanelBusinessException("contribution.disabled", 403, "节点贡献功能未开启");
        }
        if (myContributions(userId).size() >= contribution.maxPerUser()) {
            throw new McpanelBusinessException("contribution.limit", 409, "每人最多 " + contribution.maxPerUser() + " 个贡献节点");
        }
        if (machineSpec != null && machineSpec.length() > 512
                || bandwidth != null && bandwidth.length() > 128
                || onlineHours != null && onlineHours.length() > 128
                || note != null && note.length() > 512) {
            throw McpanelBusinessException.invalid("申请信息过长");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", UUID.randomUUID().toString().replace("-", ""));
        document.put("userId", String.valueOf(userId));
        document.put("machineSpec", trim(machineSpec));
        document.put("bandwidth", trim(bandwidth));
        document.put("onlineHours", trim(onlineHours));
        document.put("note", trim(note));
        document.put("status", contribution.reviewRequired() ? "pending" : "approved");
        document.put("limits", Map.of(
                "maxInstances", contribution.maxInstances(),
                "maxCpuMillis", contribution.maxCpuMillis(),
                "maxMemoryMb", contribution.maxMemoryMb()));
        document.put("createdAt", System.currentTimeMillis());
        document.put("updatedAt", document.get("createdAt"));
        documents.save(COLLECTION, String.valueOf(document.get("id")), document);
        return sanitize(document);
    }

    public List<Map<String, Object>> myContributions(long userId) {
        List<Map<String, Object>> mine = new ArrayList<>();
        int page = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findByField(COLLECTION, "userId", String.valueOf(userId), page, PAGE_SIZE);
            mine.addAll(batch);
            page++;
        } while (batch.size() == PAGE_SIZE);
        return mine.stream().map(this::sanitize).toList();
    }

    public void withdraw(long userId, String id) {
        Map<String, Object> document = documents.findById(COLLECTION, id)
                .orElseThrow(() -> McpanelBusinessException.notFound("申请不存在"));
        if (!String.valueOf(document.get("userId")).equals(String.valueOf(userId))) {
            throw McpanelBusinessException.notFound("申请不存在");
        }
        documents.delete(COLLECTION, id);
    }

    // ---------- 管理端 ----------

    public Map<String, Object> adminPage(int page, int size, String status) {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, PAGE_SIZE);
            all.addAll(batch);
            p++;
        } while (batch.size() == PAGE_SIZE);
        List<Map<String, Object>> filtered = all.stream()
                .filter(c -> status == null || status.isBlank() || status.equals(c.get("status")))
                .sorted((a, b) -> Long.compare(((Number) b.get("createdAt")).longValue(),
                        ((Number) a.get("createdAt")).longValue()))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        return result;
    }

    public Map<String, Object> review(String reviewer, String id, boolean approve, String reason) {
        Map<String, Object> document = documents.findById(COLLECTION, id)
                .orElseThrow(() -> McpanelBusinessException.notFound("申请不存在"));
        if (!"pending".equals(String.valueOf(document.get("status")))) {
            throw new McpanelBusinessException("contribution.reviewed", 409, "该申请已审核");
        }
        document.put("status", approve ? "approved" : "rejected");
        document.put("reviewReason", trim(reason));
        document.put("reviewedBy", reviewer);
        document.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, id, document);
        return document;
    }

    private Map<String, Object> sanitize(Map<String, Object> document) {
        // 玩家面回显不含审核人等管理字段以外的敏感信息；limits 属于本人申请内容。
        Map<String, Object> view = new LinkedHashMap<>(document);
        view.remove("reviewedBy");
        return view;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
