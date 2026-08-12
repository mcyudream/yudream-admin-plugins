package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotActivityServiceTest {
    @Test
    void persistsSafeMetadataFiltersAndAggregatesEvents() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotActivityService service = new AiChatbotActivityService(documents);
        service.record("milky", "10001", "qq-1", "user-1", "REPLY_TRIGGERED", "MENTION", true);
        service.record("milky", "10001", "qq-1", "user-1", "REPLY_COMPLETED", "MENTION", true);
        service.record("milky", "10002", "qq-2", "", "REPLY_REJECTED_UNBOUND", "MENTION", false);

        Map<String, Object> document = documents.collection("activity-event").getFirst();
        assertFalse(document.containsKey("content"));
        assertFalse(document.containsKey("message"));
        assertEquals(2, service.overview(null, null, "milky", "10001", null, null).get("total"));
        assertEquals(1, service.page(null, null, "milky", null, "REPLY_COMPLETED", "qq-1", 1, 20).get("total"));
        assertEquals(1, service.users(null, null, "milky", "10001", null, null).size());
        assertTrue(service.heatmap(null, null, "milky", "10001", null, null, ZoneId.of("UTC")).getFirst().containsKey("hour"));
    }

    @Test
    void timelineSupportsHourAndTimezoneBuckets() {
        InMemoryDocuments documents = new InMemoryDocuments();
        documents.save("activity-event", "one", event("one", 0));
        documents.save("activity-event", "two", event("two", 3_600_000));
        AiChatbotActivityService service = new AiChatbotActivityService(documents);
        List<Map<String, Object>> hours = service.timeline(null, null, null, null, null, null, "hour", ZoneId.of("Asia/Shanghai"));
        assertEquals(2, hours.size());
        assertTrue(((String) hours.getFirst().get("bucket")).contains("08:00"));
        assertEquals(1, service.timeline(null, null, null, null, null, null, "day", ZoneId.of("UTC")).size());
    }

    @Test
    void recordPropagatesStorageFailureForPluginToSafelyHandle() {
        AiChatbotActivityService service = new AiChatbotActivityService(new FailingDocuments());
        assertThrows(IllegalStateException.class, () -> service.record("milky", "100", "qq", "1", "MESSAGE_RECEIVED", "", true));
    }

    private static Map<String, Object> event(String id, long occurredAt) { return Map.of("id", id, "occurredAt", occurredAt, "connectionId", "milky", "channelId", "100", "platformUserId", "qq", "userId", "1", "type", "MESSAGE_RECEIVED", "mode", "", "success", true); }
    private static final class FailingDocuments implements PluginDocumentStore {
        @Override public Map<String, Object> save(String collection, String id, Map<String, Object> document) { throw new IllegalStateException("storage down"); }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.empty(); }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return List.of(); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return 0; }
        @Override public void delete(String collection, String id) { }
    }
    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, LinkedHashMap<String, Map<String, Object>>> values = new LinkedHashMap<>();
        @Override public Map<String, Object> save(String collection, String id, Map<String, Object> document) { Map<String, Object> copy = new LinkedHashMap<>(document); values.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, copy); return copy; }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.ofNullable(values.getOrDefault(collection, new LinkedHashMap<>()).get(id)); }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { List<Map<String, Object>> rows = collection(collection); int start = Math.max(0, page - 1) * size; return start >= rows.size() ? List.of() : rows.subList(start, Math.min(rows.size(), start + size)); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return collection(collection).size(); }
        @Override public void delete(String collection, String id) { values.getOrDefault(collection, new LinkedHashMap<>()).remove(id); }
        List<Map<String, Object>> collection(String collection) { return new ArrayList<>(values.getOrDefault(collection, new LinkedHashMap<>()).values()); }
    }
}
