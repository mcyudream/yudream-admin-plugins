package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 原版客户端 jar 库（{@code client_jar} 集合）：管理员预上传的各版本 client jar，
 * 新建地图时按版本直接选用，渲染时与手动上传的 jar 走同一条物化路径。
 *
 * <p>以版本号作为文档 id（同一版本只保留一条，重复上传即覆盖）；平台文件本体仍在
 * 宿主文件存储里，这里只记 id 与展示元信息——与 {@code map_project} 引用 world/client jar
 * 的方式一致，插件不持有文件字节。</p>
 */
public final class ClientJarLibrary {

    public static final String COLLECTION = "client_jar";

    /** 宿主文档库单页上限（超过要翻页）。 */
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public ClientJarLibrary(PluginDocumentStore store) {
        this.store = store;
    }

    /** 全部条目，按版本倒序（新的在前）。 */
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        long count = store.count(COLLECTION);
        int pages = (int) Math.max(1, Math.ceil(count / (double) PAGE_SIZE));
        for (int page = 1; page <= pages; page++) {
            for (Map<String, Object> document : store.findAll(COLLECTION, page, PAGE_SIZE)) {
                entries.add(toEntry(document));
            }
        }
        entries.sort((left, right) -> right.version().compareToIgnoreCase(left.version()));
        return List.copyOf(entries);
    }

    /** 新增或覆盖一个版本。 */
    public Entry put(String version, String fileId, String fileName, long sizeBytes) {
        String normalized = normalizeVersion(version);
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("平台文件 id 不能为空");
        }
        String name = fileName == null || fileName.isBlank() ? "client-" + normalized + ".jar" : fileName.trim();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileId", fileId.trim());
        document.put("fileName", name);
        document.put("sizeBytes", Math.max(0, sizeBytes));
        document.put("createdAt", Instant.now().toString());
        store.save(COLLECTION, normalized, document);
        return new Entry(normalized, fileId.trim(), name, Math.max(0, sizeBytes), Instant.now().toString());
    }

    public Optional<Entry> find(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return store.findById(COLLECTION, normalizeVersion(version)).map(this::toEntry);
    }

    public boolean remove(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        store.delete(COLLECTION, normalizeVersion(version));
        return true;
    }

    /** 版本号规范化：小写、去首尾空白；空或超长视为非法。 */
    private static String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("版本号过长（≤32 字符）");
        }
        return normalized;
    }

    private Entry toEntry(Map<String, Object> document) {
        return new Entry(
                stringOf(document.get("_id"), stringOf(document.get("id"), "")),
                stringOf(document.get("fileId"), ""),
                stringOf(document.get("fileName"), ""),
                longOf(document.get("sizeBytes")),
                stringOf(document.get("createdAt"), ""));
    }

    private static String stringOf(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw);
        return value.isBlank() ? fallback : value;
    }

    private static long longOf(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 库里的一条客户端 jar。
     *
     * @param version   版本号（即文档 id，如 {@code 1.21.8}）
     * @param fileId    平台文件 id
     * @param fileName  上传时的原始文件名（引擎按文件名判版本，建议带版本号）
     * @param sizeBytes 文件大小
     * @param createdAt 上传时间
     */
    public record Entry(String version, String fileId, String fileName, long sizeBytes, String createdAt) {
    }
}
