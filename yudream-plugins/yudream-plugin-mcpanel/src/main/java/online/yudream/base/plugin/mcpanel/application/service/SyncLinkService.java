package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子服数据同步（软链接模式，Tier A 全用现有节点文件通道）：
 * - 链接：target ← source，固定路径清单（白名单）+ 目标级 overrides（跳过路径，默认随软链接生效）；
 * - 同步：源多路径打 zip（file.zip）→ 面板分块拉回（file.download.chunk）→ 上传目标（file.upload.*）
 *   → 解压覆盖（file.unzip）→ 清理临时包。单向覆盖式，不删除目标多余文件。
 * - 真正的容器间 symlink 不可行（每实例独立 bind mount）；这是「同步固定文件」的等价实现，
 *   跨节点同样可用（字节经面板中转，大文件较慢——白名单限定 plugins/配置 类小体积路径）。
 * 数据存 mcpanel_sync_links（id = target 实例 id），随实例删除清理。
 */
public class SyncLinkService {

    public interface FilesGateway {
        Map<String, Object> invoke(String scopeKey, String instanceId, String method, Map<String, Object> args);
    }

    /** 上传通道（面板→节点分块上传，带 scope 校验）。 */
    public interface Uploader {
        void upload(String actor, String scopeKey, String instanceId, String name, byte[] bytes);
    }

    private static final String COLLECTION = "mcpanel_sync_links";
    private static final String BUNDLE = "mcpanel-sync-bundle.zip";
    private static final long MAX_BUNDLE_BYTES = 512L * 1024 * 1024;
    private static final int CHUNK_BYTES = 96 * 1024;

    /** 默认同步清单：插件目录 + 根级/拆分配置（世界数据默认不同步）。 */
    public static final List<String> DEFAULT_PATHS = List.of(
            "plugins", "config", "server.properties", "spigot.yml", "bukkit.yml",
            "paper.yml", "config/paper-global.yml", "config/paper-world-defaults.yml",
            "velocity.toml", "config.yml");

    private final PluginDocumentStore documents;
    private final McpanelInstanceRepository instanceRepository;
    private final McpanelNodeRepository nodeRepository;
    private final FilesGateway files;
    private final Uploader uploader;
    private final McpanelInstanceAppService.AuditRecorder audit;

    public SyncLinkService(PluginDocumentStore documents, McpanelInstanceRepository instanceRepository,
                           McpanelNodeRepository nodeRepository, FilesGateway files, Uploader uploader,
                           McpanelInstanceAppService.AuditRecorder audit) {
        this.documents = documents;
        this.instanceRepository = instanceRepository;
        this.nodeRepository = nodeRepository;
        this.files = files;
        this.uploader = uploader;
        this.audit = audit;
    }

    public Map<String, Object> linkOf(String targetId) {
        Map<String, Object> doc = documents.findById(COLLECTION, targetId).orElse(null);
        if (doc == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>(doc);
        McpanelInstance source = instanceRepository.findById(String.valueOf(doc.get("sourceInstanceId"))).orElse(null);
        view.put("sourceName", source == null ? String.valueOf(doc.get("sourceInstanceId")) : source.name());
        return view;
    }

    /** 建立/替换软链接（target ← source）；paths 越界/为空拒绝。 */
    public Map<String, Object> link(String actor, String targetId, String sourceId, List<String> paths) {
        McpanelInstance target = requireInstance(targetId);
        McpanelInstance source = instanceRepository.findById(sourceId)
                .orElseThrow(() -> McpanelBusinessException.invalid("源实例不存在"));
        if (targetId.equals(sourceId)) {
            throw McpanelBusinessException.invalid("不能与自身建立软链接");
        }
        List<String> clean = sanitize(paths);
        if (clean.isEmpty()) {
            clean = DEFAULT_PATHS;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("targetInstanceId", targetId);
        doc.put("sourceInstanceId", sourceId);
        doc.put("paths", clean);
        doc.put("overrides", List.of());
        doc.put("mode", "softlink");
        long now = System.currentTimeMillis();
        doc.put("createdAt", now);
        doc.put("lastSyncAt", 0L);
        documents.save(COLLECTION, targetId, doc);
        if (audit != null) {
            audit.record(actor, "sync.link.save", "instance", targetId,
                    "软链接 ← " + source.name() + "（" + clean.size() + " 路径）", target.tenantId());
        }
        return linkOf(targetId);
    }

    public void unlink(String actor, String targetId) {
        McpanelInstance target = requireInstance(targetId);
        if (documents.findById(COLLECTION, targetId).isEmpty()) {
            return;
        }
        documents.delete(COLLECTION, targetId);
        if (audit != null) {
            audit.record(actor, "sync.link.delete", "instance", targetId, "解除软链接", target.tenantId());
        }
    }

    /** 立即同步：源打 zip → 面板中转 → 目标解压覆盖（跳过 overrides 路径）。 */
    public Map<String, Object> syncNow(String actor, String scopeKey, String targetId) {
        Map<String, Object> doc = documents.findById(COLLECTION, targetId)
                .orElseThrow(() -> McpanelBusinessException.invalid("该实例没有软链接（先在创建向导或此处建立）"));
        String sourceId = String.valueOf(doc.get("sourceInstanceId"));
        McpanelInstance target = requireInstance(targetId);
        McpanelInstance source = instanceRepository.findById(sourceId)
                .orElseThrow(() -> McpanelBusinessException.invalid("源实例已删除，软链接失效"));
        List<String> effective = effectivePaths(doc);
        if (effective.isEmpty()) {
            throw McpanelBusinessException.invalid("有效同步路径为空（全部被 overrides 跳过）");
        }
        // 1) 源打包
        files.invoke(scopeKey, sourceId, "zip", Map.of("paths", effective, "dest", BUNDLE));
        // 2) 分块拉回
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            Map<String, Object> chunk = files.invoke(scopeKey, sourceId, "download.chunk",
                    Map.of("path", BUNDLE, "offset", offset, "length", CHUNK_BYTES));
            String encoded = String.valueOf(chunk.getOrDefault("content", ""));
            byte[] bytes = decode(encoded);
            if (bytes.length == 0) {
                break;
            }
            buffer.writeBytes(bytes);
            offset += bytes.length;
            if (buffer.size() > MAX_BUNDLE_BYTES) {
                files.invoke(scopeKey, sourceId, "delete", Map.of("path", BUNDLE));
                throw McpanelBusinessException.invalid("同步包超过 512MB：请缩小同步路径范围");
            }
            if (bytes.length < CHUNK_BYTES) {
                break;
            }
        }
        files.invoke(scopeKey, sourceId, "delete", Map.of("path", BUNDLE));
        byte[] bundle = buffer.toByteArray();
        if (bundle.length == 0) {
            throw McpanelBusinessException.invalid("源实例没有可同步的文件（路径都不存在）");
        }
        // 3) 上传目标并解压覆盖 + 清理
        uploader.upload(actor, scopeKey, targetId, BUNDLE, bundle);
        try {
            files.invoke(scopeKey, targetId, "unzip", Map.of("path", BUNDLE, "dest", ""));
        }
        finally {
            files.invoke(scopeKey, targetId, "delete", Map.of("path", BUNDLE));
        }
        Map<String, Object> updated = new LinkedHashMap<>(doc);
        updated.put("lastSyncAt", System.currentTimeMillis());
        documents.save(COLLECTION, targetId, updated);
        if (audit != null) {
            audit.record(actor, "sync.run", "instance", targetId,
                    String.format("软链接同步自 %s（%.1f MB）", sourceId, bundle.length / 1024.0 / 1024.0),
                    target.tenantId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", true);
        result.put("bytes", bundle.length);
        result.put("paths", effective);
        result.put("lastSyncAt", System.currentTimeMillis());
        return result;
    }

    /** 实例删除时清理链接（作为源或目标都清）。 */
    public void evict(String instanceId) {
        documents.delete(COLLECTION, instanceId);
        // 作为源被引用的链接一并失效删除。
        for (Map<String, Object> doc : loadAll()) {
            if (instanceId.equals(String.valueOf(doc.get("sourceInstanceId")))) {
                documents.delete(COLLECTION, String.valueOf(doc.get("targetInstanceId")));
            }
        }
    }

    // ---------- 内部 ----------

    private static List<String> effectivePaths(Map<String, Object> doc) {
        List<String> paths = stringList(doc.get("paths"));
        List<String> overrides = stringList(doc.get("overrides"));
        List<String> effective = new ArrayList<>();
        for (String path : paths) {
            if (!overrides.contains(path)) {
                effective.add(path);
            }
        }
        return effective;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> rows) {
            for (Object row : rows) {
                if (row != null && !String.valueOf(row).isBlank()) {
                    out.add(String.valueOf(row));
                }
            }
        }
        return out;
    }

    private static List<String> sanitize(List<String> paths) {
        List<String> out = new ArrayList<>();
        if (paths == null) {
            return out;
        }
        for (String path : paths) {
            String clean = path == null ? "" : path.trim();
            if (clean.isEmpty() || clean.startsWith("/") || clean.contains("..") || clean.contains("\\")
                    || clean.contains("\0") || clean.length() > 128) {
                throw McpanelBusinessException.invalid("同步路径非法：" + path);
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
        }
        if (out.size() > 32) {
            throw McpanelBusinessException.invalid("同步路径最多 32 条");
        }
        return out;
    }

    private static byte[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(encoded);
        }
        catch (RuntimeException error) {
            return encoded.getBytes(StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> loadAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, page, 200);
            all.addAll(batch);
            page++;
        } while (batch.size() == 200);
        return all;
    }

    private McpanelInstance requireInstance(String instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
    }
}
