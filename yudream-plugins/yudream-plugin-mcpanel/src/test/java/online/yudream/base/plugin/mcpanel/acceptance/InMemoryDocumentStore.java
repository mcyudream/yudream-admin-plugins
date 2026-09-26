package online.yudream.base.plugin.mcpanel.acceptance;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 线程安全内存文档存储（测试/验收 fixture，无 JUnit 依赖）。
 * 语义与宿主实现对齐：findAll/findByField 页码 1 起；updateIfFieldAtMost 为
 * “字段值 ≤ maximum 才整体替换文档”的 CAS。
 */
public class InMemoryDocumentStore implements PluginDocumentStore {

    private final Map<String, LinkedHashMap<String, Map<String, Object>>> collections = new HashMap<>();

    /**
     * 仿真严格沙盒：拒绝顶层 null 键/值（历史沙盒 Map.copyOf 对 null NPE）。
     * 生产仓储必须在上游剔除 null（stripNulls）后才能通过本桩，防止虚假绿。
     */
    private static void rejectNullEntries(String op, Map<String, Object> document) {
        if (document == null) {
            return;
        }
        document.forEach((key, value) -> {
            if (key == null || value == null) {
                throw new IllegalArgumentException(
                        op + ": null key/value not supported by sandbox emulation (key=" + key + ")");
            }
        });
    }

    @Override
    public synchronized Map<String, Object> save(String collection, String id, Map<String, Object> document) {
        rejectNullEntries("documents.save", document);
        LinkedHashMap<String, Map<String, Object>> rows =
                collections.computeIfAbsent(collection, key -> new LinkedHashMap<>());
        rows.put(id, copy(document));
        return copy(document);
    }

    @Override
    public synchronized Optional<Map<String, Object>> findById(String collection, String id) {
        Map<String, Object> row = collections.getOrDefault(collection, new LinkedHashMap<>()).get(id);
        return Optional.ofNullable(row).map(this::copy);
    }

    @Override
    public synchronized List<Map<String, Object>> findAll(String collection, int page, int size) {
        List<Map<String, Object>> rows = new ArrayList<>(collections
                .getOrDefault(collection, new LinkedHashMap<>()).values());
        return slice(rows, page, size);
    }

    @Override
    public synchronized List<Map<String, Object>> findByField(String collection, String field,
                                                              Object value, int page, int size) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : collections.getOrDefault(collection, new LinkedHashMap<>()).values()) {
            Object candidate = row.get(field);
            if (candidate == null ? value == null : candidate.equals(value)) {
                rows.add(row);
            }
        }
        return slice(rows, page, size);
    }

    @Override
    public synchronized long count(String collection) {
        return collections.getOrDefault(collection, new LinkedHashMap<>()).size();
    }

    @Override
    public synchronized boolean updateIfFieldAtMost(String collection, String id, String field,
                                                    long maximum, Map<String, Object> updates) {
        rejectNullEntries("documents.updateIfFieldAtMost", updates);
        Map<String, Object> row = collections.getOrDefault(collection, new LinkedHashMap<>()).get(id);
        if (row == null) {
            return false;
        }
        Object current = row.get(field);
        long currentLong = current instanceof Number number ? number.longValue() : Long.MIN_VALUE;
        if (currentLong > maximum) {
            return false;
        }
        collections.get(collection).put(id, copy(updates));
        return true;
    }

    @Override
    public synchronized void delete(String collection, String id) {
        collections.getOrDefault(collection, new LinkedHashMap<>()).remove(id);
    }

    /** 清空全部集合（测试隔离用）。 */
    public synchronized void clear() {
        collections.clear();
    }

    private List<Map<String, Object>> slice(List<Map<String, Object>> rows, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(from, to)) {
            result.add(copy(row));
        }
        return result;
    }

    private Map<String, Object> copy(Map<String, Object> document) {
        return document == null ? new LinkedHashMap<>() : new LinkedHashMap<>(document);
    }
}
