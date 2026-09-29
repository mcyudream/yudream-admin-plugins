package online.yudream.base.plugin.projectprogress.support;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 测试用文档存储：按集合维护文档，语义与宿主文档存储一致（覆盖保存、分页扫描、按字段查询），
 * 让 project-progress 的文档仓储在测试里走真实代码路径。
 */
public class FakeDocumentStore implements PluginDocumentStore {

    private final Map<String, Map<String, Map<String, Object>>> collections = new LinkedHashMap<>();

    @Override
    public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
        Map<String, Object> stored = new LinkedHashMap<>(document);
        stored.put("id", id);
        documentsOf(collection).put(id, stored);
        return new LinkedHashMap<>(stored);
    }

    @Override
    public Optional<Map<String, Object>> findById(String collection, String id) {
        Map<String, Object> found = documentsOf(collection).get(id);
        return found == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(found));
    }

    @Override
    public List<Map<String, Object>> findAll(String collection, int page, int size) {
        return slice(new ArrayList<>(documentsOf(collection).values()), page, size);
    }

    @Override
    public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
        List<Map<String, Object>> matched = documentsOf(collection).values().stream()
                .filter(document -> Objects.equals(document.get(field), value))
                .toList();
        return slice(matched, page, size);
    }

    @Override
    public long count(String collection) {
        return documentsOf(collection).size();
    }

    @Override
    public void delete(String collection, String id) {
        documentsOf(collection).remove(id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> slice(List<Map<String, Object>> records, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int from = Math.min(records.size(), (safePage - 1) * safeSize);
        int to = Math.min(records.size(), from + safeSize);
        return records.subList(from, to).stream()
                .map(document -> (Map<String, Object>) new LinkedHashMap<>(document))
                .toList();
    }

    private Map<String, Map<String, Object>> documentsOf(String collection) {
        return collections.computeIfAbsent(collection, key -> new LinkedHashMap<>());
    }
}
