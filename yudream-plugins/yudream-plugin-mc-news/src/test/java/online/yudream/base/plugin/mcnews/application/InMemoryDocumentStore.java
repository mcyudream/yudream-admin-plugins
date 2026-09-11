package online.yudream.base.plugin.mcnews.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/** 测试用内存文档存储，模拟宿主行为：save 覆写 id、按集合隔离。 */
public final class InMemoryDocumentStore implements PluginDocumentStore {
    private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

    private Map<String, Map<String, Object>> collection(String name) {
        return collections.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
    }

    @Override
    public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
        Map<String, Object> doc = new HashMap<>(document);
        doc.put("id", id);
        collection(collection).put(id, doc);
        return doc;
    }

    @Override
    public Optional<Map<String, Object>> findById(String collection, String id) {
        Map<String, Object> doc = collection(collection).get(id);
        return doc == null ? Optional.empty() : Optional.of(new HashMap<>(doc));
    }

    @Override
    public List<Map<String, Object>> findAll(String collection, int page, int size) {
        return collection(collection).values().stream()
                .<Map<String, Object>>map(doc -> new HashMap<>(doc))
                .toList();
    }

    @Override
    public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
        return findAll(collection, page, size).stream()
                .filter(doc -> value.equals(doc.get(field)))
                .toList();
    }

    @Override
    public long count(String collection) {
        return collection(collection).size();
    }

    @Override
    public void delete(String collection, String id) {
        collection(collection).remove(id);
    }
}
