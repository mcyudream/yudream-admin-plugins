package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfile;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileAnalysis;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileObservation;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotMemoryProfileUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotMemoryProfileServiceTest {
    @Test
    void observingKeepsRecentMessageFactAndBehaviorCounts() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(documents);
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        service.recordReply("milky", "100", "1", "qq-1", "Alice", "", "COMPLETED");

        AiChatbotMemoryProfile profile = service.get("milky:100:1");
        assertEquals(1, profile.observedMessageCount());
        assertEquals(1, profile.replyTriggeredCount());
        assertEquals(1, profile.replyCompletedCount());
        assertEquals("recent_message", profile.facts().getFirst().key());
        Map<String, Object> document = documents.findById("memory-profile", "milky:100:1").orElseThrow();
        assertFalse(document.containsKey("content"));
    }

    @Test
    void controlledUpdatePreservesIdentityCountsAndAutomaticFacts() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        AiChatbotMemoryProfile before = service.get("milky:100:1");
        AiChatbotMemoryProfile updated = service.update(new AiChatbotMemoryProfileUpdateRequest(before.id(), false, "admin summary", List.of("vip"), List.of(new AiChatbotMemoryFact("interest", "Java", 0.8d, true, 0))));

        assertEquals("milky", updated.connectionId());
        assertEquals("100", updated.channelId());
        assertEquals("1", updated.userId());
        assertEquals("qq-1", updated.platformUserId());
        assertEquals("Alice", updated.nickname());
        assertEquals(before.observedMessageCount(), updated.observedMessageCount());
        assertEquals(before.lastActivityAt(), updated.lastActivityAt());
        assertTrue(updated.updatedAt() >= before.updatedAt());
        assertEquals(List.of("interest", "recent_message"), updated.facts().stream().map(AiChatbotMemoryFact::key).toList());
    }

    @Test
    void rejectsAdministratorRecentMessageFact() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        assertThrows(IllegalArgumentException.class, () -> service.update(new AiChatbotMemoryProfileUpdateRequest("milky:100:1", true, "", List.of(), List.of(new AiChatbotMemoryFact("recent_message", "forged", 1d, true, 0)))));
    }

    @Test
    void observationsAreBoundedTruncatedAndNewestFirst() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(documents);
        for (int index = 0; index < 45; index++) service.observe("milky", "100", "1", "qq-1", "Alice", "", "message-" + index + " " + "x".repeat(200));
        service.recordReply("milky", "100", "1", "qq-1", "Alice", "", "COMPLETED");

        List<AiChatbotProfileObservation> observations = service.observations("milky:100:1");
        assertEquals(40, observations.size());
        assertTrue(observations.stream().allMatch(row -> row.content().length() <= 120));
        assertTrue(observations.getFirst().content().startsWith("message-44"));
        assertTrue(observations.getLast().content().startsWith("message-5"));
        assertTrue(observations.stream().allMatch(row -> row.content().startsWith("message-")));
    }

    @Test
    void applyAnalysisProtectsApprovedManualFacts() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        service.update(new AiChatbotMemoryProfileUpdateRequest("milky:100:1", true, "admin summary", List.of("vip"), List.of(new AiChatbotMemoryFact("interest", "Java", 0.9d, true, 0))));

        AiChatbotMemoryProfile analyzed = service.applyAnalysis("milky:100:1", new AiChatbotProfileAnalysis("ai summary", "开朗健谈", "主动互动", List.of("新标签"),
                List.of(new AiChatbotMemoryFact("interest", "Python", 0.8d, false, 0), new AiChatbotMemoryFact("habit", "深夜发言", 0.7d, false, 0))));

        assertEquals("ai summary", analyzed.summary());
        assertEquals("开朗健谈", analyzed.personality());
        assertEquals("主动互动", analyzed.interactionStyle());
        assertEquals(List.of("vip", "新标签"), analyzed.tags());
        assertTrue(analyzed.lastAnalyzedAt() > 0);
        assertEquals(1, analyzed.facts().stream().filter(fact -> fact.key().equals("interest")).count());
        assertEquals("Java", analyzed.facts().stream().filter(fact -> fact.key().equals("interest")).findFirst().orElseThrow().value());
        assertTrue(analyzed.facts().stream().anyMatch(fact -> fact.key().equals("habit") && !fact.approved()));
        assertTrue(analyzed.facts().stream().anyMatch(fact -> fact.key().equals("recent_message")));
    }

    @Test
    void applyAnalysisCorrectsUnapprovedFactInsteadOfFossilizingIt() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        service.applyAnalysis("milky:100:1", new AiChatbotProfileAnalysis("", "", "", List.of(),
                List.of(new AiChatbotMemoryFact("interest", "足球", 0.6d, false, 0))));

        AiChatbotMemoryProfile refined = service.applyAnalysis("milky:100:1", new AiChatbotProfileAnalysis("", "", "", List.of(),
                List.of(new AiChatbotMemoryFact("interest", "篮球", 0.9d, false, 0))));

        List<AiChatbotMemoryFact> interests = refined.facts().stream().filter(fact -> fact.key().equals("interest")).toList();
        assertEquals(1, interests.size());
        assertEquals("篮球", interests.getFirst().value());
        assertFalse(interests.getFirst().approved());
    }

    @Test
    void applyAnalysisKeepsCurrentFieldsWhenAiResultBlank() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        service.update(new AiChatbotMemoryProfileUpdateRequest("milky:100:1", true, "admin summary", List.of("vip"), List.of()));

        AiChatbotMemoryProfile analyzed = service.applyAnalysis("milky:100:1", new AiChatbotProfileAnalysis("", "", "", List.of(), List.of()));

        assertEquals("admin summary", analyzed.summary());
        assertEquals(List.of("vip"), analyzed.tags());
        assertTrue(analyzed.lastAnalyzedAt() > 0);
    }

    @Test
    void deletingProfileAlsoRemovesObservations() {
        AiChatbotMemoryProfileService service = new AiChatbotMemoryProfileService(new InMemoryDocuments());
        service.observe("milky", "100", "1", "qq-1", "Alice", "", "hello world");
        service.delete("milky:100:1");
        assertTrue(service.observations("milky:100:1").isEmpty());
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();
        @Override public Map<String, Object> save(String collection, String id, Map<String, Object> document) { Map<String, Object> copy = new HashMap<>(document); values.put(collection + ":" + id, copy); return copy; }
        @Override public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.ofNullable(values.get(collection + ":" + id)); }
        @Override public List<Map<String, Object>> findAll(String collection, int page, int size) { return List.of(); }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public long count(String collection) { return values.keySet().stream().filter(key -> key.startsWith(collection + ":")).count(); }
        @Override public void delete(String collection, String id) { values.remove(collection + ":" + id); }
    }
}
