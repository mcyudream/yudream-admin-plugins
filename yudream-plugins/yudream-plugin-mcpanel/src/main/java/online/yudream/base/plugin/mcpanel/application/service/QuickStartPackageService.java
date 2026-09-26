package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 快速开始向导包（对标 MCSM AppPackages / QuickStart）：
 * 管理员维护的「游戏包」——核心类型 + 推荐版本 + Java 运行时 + 推荐资源 + 说明。
 * 安装时展开为创建向导预填项。
 */
public class QuickStartPackageService {

    private static final String COLLECTION = "mcpanel_quickstart_packages";

    private final PluginDocumentStore documents;

    public QuickStartPackageService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public void seedDefaultsIfEmpty() {
        if (!loadAll().isEmpty()) {
            return;
        }
        seed("pkg-paper-1-21", "Paper 1.21 生存服", "paper", "1.21.4", "java21",
                2048, 2000, "标准插件服，适合中小规模生存/创造。");
        seed("pkg-purpur-1-21", "Purpur 1.21", "purpur", "1.21.4", "java21",
                2048, 2000, "Purpur 优化分支，兼容 Paper 插件。");
        seed("pkg-fabric-1-21", "Fabric 1.21 模组服", "fabric", "1.21.1", "java21",
                4096, 2000, "轻量模组加载器。");
        seed("pkg-vanilla-1-21", "原版 1.21", "vanilla", "1.21.4", "java21",
                1024, 1000, "Mojang 官方服务端。");
        seed("pkg-velocity", "Velocity 代理", "velocity", "latest", "java21",
                1024, 500, "群组服代理节点。");
    }

    private void seed(String id, String name, String kind, String mcVersion, String javaImageId,
                      long memoryMb, long cpuMillis, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("kind", kind);
        item.put("mcVersion", mcVersion);
        item.put("javaImageId", javaImageId);
        item.put("memoryMb", memoryMb);
        item.put("cpuMillis", cpuMillis);
        item.put("diskMb", 10240);
        item.put("autoDownloadCore", true);
        item.put("note", note);
        item.put("builtin", true);
        item.put("enabled", true);
        item.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, id, item);
    }

    public Map<String, Object> page(int page, int size, String keyword) {
        List<Map<String, Object>> filtered = loadAll().stream()
                .filter(item -> keyword == null || keyword.isBlank()
                        || String.valueOf(item.getOrDefault("name", "")).toLowerCase().contains(keyword.toLowerCase())
                        || String.valueOf(item.getOrDefault("kind", "")).toLowerCase().contains(keyword.toLowerCase()))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 20 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        return result;
    }

    public List<Map<String, Object>> options() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : loadAll()) {
            if (!Boolean.FALSE.equals(item.get("enabled"))) {
                out.add(item);
            }
        }
        return out;
    }

    public Map<String, Object> save(Map<String, Object> input) {
        String name = String.valueOf(input.getOrDefault("name", "")).trim();
        if (name.isBlank()) {
            throw online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.invalid("名称不能为空");
        }
        String id = String.valueOf(input.getOrDefault("id", "")).trim();
        if (id.isBlank()) {
            id = "pkg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("kind", String.valueOf(input.getOrDefault("kind", "paper")));
        item.put("mcVersion", String.valueOf(input.getOrDefault("mcVersion", "")));
        item.put("javaImageId", String.valueOf(input.getOrDefault("javaImageId", "java21")));
        item.put("memoryMb", number(input.get("memoryMb"), 2048));
        item.put("cpuMillis", number(input.get("cpuMillis"), 1000));
        item.put("diskMb", number(input.get("diskMb"), 10240));
        item.put("autoDownloadCore", !Boolean.FALSE.equals(input.get("autoDownloadCore")));
        item.put("note", String.valueOf(input.getOrDefault("note", "")));
        item.put("builtin", Boolean.TRUE.equals(input.get("builtin")));
        item.put("enabled", !Boolean.FALSE.equals(input.get("enabled")));
        item.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, id, item);
        return item;
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.invalid("包 ID 不能为空");
        }
        documents.delete(COLLECTION, id);
    }

    public Map<String, Object> get(String id) {
        return documents.findById(COLLECTION, id)
                .orElseThrow(() -> online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.notFound("快速开始包不存在"));
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private List<Map<String, Object>> loadAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, 200);
            all.addAll(batch);
            p++;
        } while (batch.size() == 200);
        return all;
    }
}
