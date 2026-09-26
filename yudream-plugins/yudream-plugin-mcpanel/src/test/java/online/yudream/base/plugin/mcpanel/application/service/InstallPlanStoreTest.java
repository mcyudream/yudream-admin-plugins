package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安装计划存根：落库清洗（null 剥除/缺字段条目丢弃）、读取回环、删除。 */
class InstallPlanStoreTest {

    /** 手写内存 fake（项目无 Mockito）。 */
    private static final class FakeDocumentStore implements PluginDocumentStore {
        private final Map<String, Map<String, Map<String, Object>>> data = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> doc) {
            data.computeIfAbsent(collection, key -> new ConcurrentHashMap<>()).put(id, new LinkedHashMap<>(doc));
            return doc;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(data.getOrDefault(collection, Map.of()).get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return new ArrayList<>(data.getOrDefault(collection, Map.of()).values());
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return List.of();
        }

        @Override
        public long count(String collection) {
            return data.getOrDefault(collection, Map.of()).size();
        }

        @Override
        public void delete(String collection, String id) {
            Map<String, Map<String, Object>> bucket = data.get(collection);
            if (bucket != null) {
                bucket.remove(id);
            }
        }
    }

    @Test
    void saveFindRoundTripCleansNulls() {
        FakeDocumentStore documents = new FakeDocumentStore();
        InstallPlanStore store = new InstallPlanStore(documents);
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("url", "https://example.com/server.jar");
        core.put("path", "server.jar");
        core.put("sha512", null); // null 必须剥除（文档存储遇 null 裸 NPE）
        plan.add(core);
        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("url", "https://cdn.example.com/a.jar");
        mod.put("path", "mods/a.jar");
        mod.put("sha512", "abc");
        mod.put("size", 12345L);
        plan.add(mod);
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("url", "https://example.com/no-path.jar");
        plan.add(broken); // 缺 path：不可重放，丢弃

        store.save("inst-1", "3 个文件", plan);
        InstallPlanStore.StoredPlan stored = store.find("inst-1").orElseThrow();
        assertEquals("3 个文件", stored.fileName());
        assertEquals(2, stored.files().size());
        assertFalse(stored.files().get(0).containsKey("sha512"));
        assertEquals(12345L, ((Number) stored.files().get(1).get("size")).longValue());
        assertTrue(stored.updatedAt() > 0);

        store.delete("inst-1");
        assertTrue(store.find("inst-1").isEmpty());
    }

    @Test
    void emptyPlanKeepsPreviousStub() {
        FakeDocumentStore documents = new FakeDocumentStore();
        InstallPlanStore store = new InstallPlanStore(documents);
        store.save("inst-2", "server.jar", List.of(Map.of(
                "url", "https://example.com/server.jar", "path", "server.jar")));
        store.save("inst-2", "ignored", List.of());
        assertEquals("server.jar", store.find("inst-2").orElseThrow().fileName());
    }
}
