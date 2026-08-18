package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotMessageLogServiceTest {

    @Test
    void keepsOnlyLast24HoursAndSamplesWithinLimit() {
        InMemoryStore store = new InMemoryStore();
        AiChatbotMessageLogService logs = new AiChatbotMessageLogService(store);

        for (int i = 0; i < 30; i++) {
            logs.log("c1", "g1", "1001", "阿明", "消息 " + i);
        }
        logs.log("c1", "g1", "1002", "阿红", "别人的消息");

        assertEquals(30, logs.countRecent("c1", "g1", "1001"));
        List<AiChatbotMessageLogService.LoggedMessage> sampled = logs.sample("c1", "g1", "1001", 5);
        assertEquals(5, sampled.size());
        assertEquals(30, logs.sample("c1", "g1", "1001", 50).size());
        assertTrue(logs.sample("c1", "g1", "1001", 0).isEmpty());
        assertEquals(1, logs.countRecent("c1", "g1", "1002"));
    }

    @Test
    void purgesExpiredMessagesWhenLogging() {
        InMemoryStore store = new InMemoryStore();
        AiChatbotMessageLogService logs = new AiChatbotMessageLogService(store);
        Map<String, Object> expired = new HashMap<>();
        expired.put("id", "old-1");
        expired.put("groupKey", "c1:g1");
        expired.put("userKey", "c1:g1:1001");
        expired.put("userId", "1001");
        expired.put("content", "一天前的消息");
        expired.put("occurredAt", System.currentTimeMillis() - 25L * 3600_000L);
        store.save(AiChatbotMessageLogService.COLLECTION, "old-1", expired);

        logs.log("c1", "g1", "1001", "阿明", "新消息");

        assertTrue(store.findById(AiChatbotMessageLogService.COLLECTION, "old-1").isEmpty());
        assertEquals(1, logs.countRecent("c1", "g1", "1001"));
    }

    /** 简化的内存文档存储，模拟宿主 PluginDocumentStore 行为。 */
    private static final class InMemoryStore implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> data = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            data.put(id, new HashMap<>(document));
            return document;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(data.get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return new ArrayList<>(data.values());
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matched = data.values().stream()
                    .filter(doc -> value.equals(doc.get(field)))
                    .toList();
            int from = Math.min((page - 1) * size, matched.size());
            int to = Math.min(from + size, matched.size());
            return new ArrayList<>(matched.subList(from, to));
        }

        @Override
        public long count(String collection) {
            return data.size();
        }

        @Override
        public void delete(String collection, String id) {
            data.remove(id);
        }
    }
}
