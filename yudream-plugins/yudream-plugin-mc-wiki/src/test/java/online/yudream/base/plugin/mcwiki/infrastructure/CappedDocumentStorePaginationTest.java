package online.yudream.base.plugin.mcwiki.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.mcwiki.application.WikiVersionService;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McVersionInfo;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

/** 宿主 MongoPluginDocumentStore 按 _id 字典序返回且单页上限 200，本测试用同样行为的假实现防回归。 */
class CappedDocumentStorePaginationTest {
    /** 模拟宿主：_id 字典序升序 + 单页硬上限 200。 */
    private static final class CappedStore implements PluginDocumentStore {
        private static final int MAX_PAGE = 200;
        private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            copy.put("id", id);
            collections.computeIfAbsent(collection, k -> new ConcurrentHashMap<>()).put(id, copy);
            return copy;
        }

        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id));
        }

        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return slice(sorted(collections.getOrDefault(collection, Map.of()).values().stream().toList()), page, size);
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
            return docs.stream().sorted(Comparator.comparing(d -> String.valueOf(d.get("id")))).toList();
        }

        private List<Map<String, Object>> slice(List<Map<String, Object>> docs, int page, int size) {
            int capped = Math.min(Math.max(size, 1), MAX_PAGE);
            int from = Math.min((Math.max(page, 1) - 1) * capped, docs.size());
            return new ArrayList<>(docs.subList(from, Math.min(from + capped, docs.size())));
        }
    }

    @Test
    void versionListReadsBeyondFirstCappedPage() {
        CappedStore store = new CappedStore();
        WikiVersionRepository repository = new WikiVersionRepository(store);
        // 字典序下 "1.20.3" 家族排在 "26.x" 之前，908 条真实清单的截断窗口恰好在 1.20.2 附近；
        // 这里造 450 条混合 id，验证翻页读取不丢尾部。
        for (int i = 0; i < 450; i++) {
            String id = i % 2 == 0 ? "1." + i : "26." + i;
            repository.replace(List.of(new McVersionInfo(id, "release", String.format("2026-01-%02d", i % 28 + 1), null, false)));
        }
        WikiVersionService service = new WikiVersionService(null, repository);
        assertEquals(450, service.list(1, 200, null, null).total());
        assertEquals(450, service.list(3, 200, null, null).total());
        assertEquals(50, service.list(3, 200, null, null).records().size());
    }

    @Test
    void resourceScanReadsBeyondFirstCappedPage() {
        CappedStore store = new CappedStore();
        WikiResourceRepository repository = new WikiResourceRepository(store);
        for (int i = 0; i < 450; i++) {
            repository.saveItem(new McWikiApi.McItemEntry("1.20.1", "minecraft:item_" + i, "item", "Item " + i, "物品 " + i, List.of(), null, ""));
        }
        assertEquals(450, repository.itemCount("1.20.1", null));
        assertEquals(450, repository.items("1.20.1", 1, 200, null).size() + repository.items("1.20.1", 2, 200, null).size() + repository.items("1.20.1", 3, 200, null).size());
    }
}
