package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实例最近一次安装计划存根（mcpanel_install_plan 集合，id = 实例 id）：
 * install.run 受理时按实例覆写，供「重试安装」在失败/卡死后原样重建计划，
 * 无需重新上传整合包或手填下载地址。实例删除时随侧车清理移除。
 * 计划条目只保留 url/path/sha512/size 四个字段并剥除 null（文档存储遇 null 会 NPE）。
 */
public class InstallPlanStore {

    /** 存根记录：展示名 + 原始计划 + 落库时间。 */
    public record StoredPlan(String fileName, List<Map<String, Object>> files, long updatedAt) {
    }

    static final String COLLECTION = "mcpanel_install_plan";
    /** 与节点 install.run 的计划上限一致。 */
    private static final int MAX_FILES = 2000;
    /** 单字段长度上限，防异常超大 URL 撑爆文档。 */
    private static final int MAX_FIELD = 2048;
    /** 单条目源回退候选上限（管理员源列表 + 官方兜底，够用且防撑爆）。 */
    private static final int MAX_URLS = 8;

    private final PluginDocumentStore documents;

    public InstallPlanStore(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /** 覆写存根；计划为空或超限时保留旧存根不动（旧的也比没有强）。 */
    public void save(String instanceId, String fileName, List<Map<String, Object>> files) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) {
            return;
        }
        List<Map<String, Object>> cleaned = new ArrayList<>(files.size());
        for (Map<String, Object> file : files) {
            if (file == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putText(entry, "url", file.get("url"));
            putText(entry, "path", file.get("path"));
            putText(entry, "sha512", file.get("sha512"));
            Object size = file.get("size");
            if (size instanceof Number number) {
                entry.put("size", number.longValue());
            }
            putUrls(entry, file.get("urls"));
            if (!entry.containsKey("url") || !entry.containsKey("path")) {
                continue; // 缺关键字段的条目无法重放
            }
            cleaned.add(entry);
        }
        if (cleaned.isEmpty()) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("fileName", fileName == null ? "" : fileName);
        doc.put("files", cleaned);
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, instanceId, doc);
    }

    public Optional<StoredPlan> find(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, instanceId).map(this::toPlan);
    }

    public void delete(String instanceId) {
        if (instanceId != null && !instanceId.isBlank()) {
            documents.delete(COLLECTION, instanceId);
        }
    }

    @SuppressWarnings("unchecked")
    private StoredPlan toPlan(Map<String, Object> doc) {
        Object files = doc.get("files");
        List<Map<String, Object>> plan = new ArrayList<>();
        if (files instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    plan.add((Map<String, Object>) map);
                }
            }
        }
        Object updatedAt = doc.get("updatedAt");
        long at = updatedAt instanceof Number number ? number.longValue() : 0L;
        return new StoredPlan(String.valueOf(doc.getOrDefault("fileName", "")), plan, at);
    }

    private static void putText(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        target.put(key, text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) : text);
    }

    /** 源回退列表（MR/CF 换源）：封顶 8 条、单条限长；null/非列表忽略。 */
    private static void putUrls(Map<String, Object> target, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>(Math.min(list.size(), MAX_URLS));
        for (Object item : list) {
            if (urls.size() >= MAX_URLS) {
                break;
            }
            if (item != null && !String.valueOf(item).isBlank()) {
                String text = String.valueOf(item);
                urls.add(text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) : text);
            }
        }
        if (urls.size() > 1) {
            target.put("urls", urls);
        }
    }
}
