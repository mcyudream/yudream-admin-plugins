package online.yudream.base.plugin.interop.infrastructure.repository;

import online.yudream.base.plugin.interop.domain.aggregate.CatalogEntry;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** mc-interop 仓储（PluginDocumentStore：settings 单文档、entries、peers）。 */
public class InteropRepository {

    public static final String SETTINGS = "settings";
    public static final String ENTRIES = "interop_entries";
    public static final String PEERS = "interop_peers";

    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public InteropRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    // ---- settings ----

    public Map<String, Object> settings() {
        return documents.findById(SETTINGS, "site")
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
    }

    public void saveSettings(Map<String, Object> document) {
        documents.save(SETTINGS, "site", document);
    }

    // ---- entries ----

    public void saveEntry(CatalogEntry entry) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", entry.name());
        document.put("summary", entry.summary());
        document.put("revision", entry.revision());
        document.put("gameVersion", entry.gameVersion());
        document.put("loader", entry.loader());
        document.put("loaderVersion", entry.loaderVersion());
        document.put("artifactFormat", entry.artifactFormat());
        document.put("artifactUrl", entry.artifactUrl());
        document.put("artifactSize", entry.artifactSize());
        document.put("sha1", entry.sha1());
        document.put("sha512", entry.sha512());
        document.put("distSystem", entry.distSystem());
        document.put("distPackId", entry.distPackId());
        document.put("distVersionId", entry.distVersionId());
        document.put("serverAddress", entry.serverAddress());
        document.put("serverPort", entry.serverPort());
        document.put("published", entry.published() ? 1L : 0L);
        document.put("updatedAt", entry.updatedAt());
        documents.save(ENTRIES, entry.id(), document);
    }

    public Optional<CatalogEntry> findEntry(String id) {
        return documents.findById(ENTRIES, id).map(document -> toEntry(id, document));
    }

    /** 全量条目（管理面分页在服务层内存过滤后切片；目录规模为管理员手工量级）。 */
    public List<CatalogEntry> allEntries() {
        List<CatalogEntry> entries = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(ENTRIES, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> document : batch) {
                entries.add(toEntry(string(document, "id"), document));
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                return entries;
            }
            page++;
        }
    }

    public long entryCount() {
        return documents.count(ENTRIES);
    }

    public void deleteEntry(String id) {
        documents.delete(ENTRIES, id);
    }

    // ---- peers ----

    public void savePeer(String siteId, Map<String, Object> document) {
        documents.save(PEERS, siteId, document);
    }

    public List<Map<String, Object>> allPeers() {
        List<Map<String, Object>> peers = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(PEERS, page, SCAN_PAGE_SIZE);
            peers.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return peers;
            }
            page++;
        }
    }

    public void deletePeer(String siteId) {
        documents.delete(PEERS, siteId);
    }

    // ---- converters ----

    private CatalogEntry toEntry(String id, Map<String, Object> document) {
        return new CatalogEntry(
                id,
                string(document, "name"),
                string(document, "summary"),
                nullableLong(document, "revision"),
                string(document, "gameVersion"),
                string(document, "loader"),
                string(document, "loaderVersion"),
                string(document, "artifactFormat"),
                string(document, "artifactUrl"),
                nullableLong(document, "artifactSize"),
                string(document, "sha1"),
                string(document, "sha512"),
                string(document, "distSystem"),
                string(document, "distPackId"),
                string(document, "distVersionId"),
                string(document, "serverAddress"),
                nullableInt(document, "serverPort"),
                ((Number) document.getOrDefault("published", 0L)).longValue() > 0,
                ((Number) document.getOrDefault("updatedAt", 0L)).longValue());
    }

    private static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long nullableLong(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer nullableInt(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
}
