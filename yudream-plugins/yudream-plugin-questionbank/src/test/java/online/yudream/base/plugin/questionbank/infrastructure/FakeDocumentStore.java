package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/** 宿主文档存储语义的内存假实现：_id 字典序升序、单页上限 200、save 覆写 id 字段。 */
public final class FakeDocumentStore implements PluginDocumentStore {
    private static final int MAX_PAGE = 200;
    private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

    public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
        Map<String, Object> copy = new HashMap<>(document);
        copy.put("id", id);
        collections.computeIfAbsent(collection, key -> new ConcurrentHashMap<>()).put(id, copy);
        return copy;
    }

    public Optional<Map<String, Object>> findById(String collection, String id) {
        return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id));
    }

    public List<Map<String, Object>> findAll(String collection, int page, int size) {
        return slice(sorted(new ArrayList<>(collections.getOrDefault(collection, Map.of()).values())), page, size);
    }

    public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
        List<Map<String, Object>> matched = collections.getOrDefault(collection, Map.of()).values().stream()
                .filter(doc -> String.valueOf(doc.get(field)).equals(String.valueOf(value))).toList();
        return slice(sorted(matched), page, size);
    }

    public long count(String collection) {
        return collections.getOrDefault(collection, Map.of()).size();
    }

    public void delete(String collection, String id) {
        collections.getOrDefault(collection, Map.of()).remove(id);
    }

    private List<Map<String, Object>> sorted(List<Map<String, Object>> docs) {
        return docs.stream().sorted(Comparator.comparing(doc -> String.valueOf(doc.get("id")))).toList();
    }

    private List<Map<String, Object>> slice(List<Map<String, Object>> docs, int page, int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE);
        int from = Math.min((Math.max(page, 1) - 1) * capped, docs.size());
        return new ArrayList<>(docs.subList(from, Math.min(from + capped, docs.size())));
    }
}
