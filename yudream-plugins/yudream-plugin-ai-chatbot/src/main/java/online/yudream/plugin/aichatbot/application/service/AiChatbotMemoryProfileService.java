package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfile;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfilePage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AiChatbotMemoryProfileService {
    private static final String COLLECTION = "memory-profile";
    private final PluginDocumentStore documents;
    public AiChatbotMemoryProfileService(PluginDocumentStore documents) { this.documents = documents; }
    public AiChatbotMemoryProfilePage page(int page, int size) { return new AiChatbotMemoryProfilePage(documents.findAll(COLLECTION, Math.max(1, page), Math.clamp(size, 1, 100)).stream().map(this::profile).toList(), documents.count(COLLECTION)); }
    public AiChatbotMemoryProfile get(String id) { return documents.findById(COLLECTION, require(id)).map(this::profile).orElseThrow(() -> new IllegalArgumentException("画像不存在")); }
    public AiChatbotMemoryProfile save(AiChatbotMemoryProfile value) { validate(value); documents.save(COLLECTION, value.id(), document(value)); return value; }
    public AiChatbotMemoryProfile enabled(String id, boolean enabled) { AiChatbotMemoryProfile current = get(id); return save(new AiChatbotMemoryProfile(current.id(), current.connectionId(), current.channelId(), current.userId(), current.platformUserId(), current.nickname(), enabled, current.summary(), current.tags(), current.facts(), System.currentTimeMillis())); }
    public void delete(String id) { documents.delete(COLLECTION, require(id)); }
    public AiChatbotMemoryProfile find(String connectionId, String channelId, String userId) { return documents.findById(COLLECTION, id(connectionId, channelId, userId)).map(this::profile).orElse(null); }
    public void observe(String connectionId, String channelId, String userId, String platformUserId, String nickname, String content) {
        if (content == null || content.isBlank()) return;
        String id = id(connectionId, channelId, userId); AiChatbotMemoryProfile current = find(connectionId, channelId, userId); long now = System.currentTimeMillis();
        List<AiChatbotMemoryFact> facts = current == null ? new ArrayList<>() : new ArrayList<>(current.facts());
        facts.removeIf(fact -> !fact.approved() && "recent_message".equals(fact.key()));
        facts.add(new AiChatbotMemoryFact("recent_message", content.length() > 300 ? content.substring(0, 300) : content, 0.2d, false, now));
        save(new AiChatbotMemoryProfile(id, connectionId, channelId, userId, platformUserId, nickname == null ? "" : nickname, current == null || current.enabled(), current == null ? "" : current.summary(), current == null ? List.of() : current.tags(), facts, now));
    }
    public static String id(String connectionId, String channelId, String userId) { return require(connectionId) + ":" + require(channelId) + ":" + require(userId); }
    private AiChatbotMemoryProfile profile(Map<String, Object> doc) { String id = text(doc, "id"); List<AiChatbotMemoryFact> facts = new ArrayList<>(); Object rawFacts = doc.get("facts"); if (rawFacts instanceof List<?> rows) for (Object row : rows) if (row instanceof Map<?, ?> map) facts.add(new AiChatbotMemoryFact(String.valueOf(map.get("key")), String.valueOf(map.get("value")), number(map.get("confidence")), Boolean.TRUE.equals(map.get("approved")), longValue(map.get("updatedAt")))); return new AiChatbotMemoryProfile(id, text(doc,"connectionId"), text(doc,"channelId"), text(doc,"userId"), text(doc,"platformUserId"), text(doc,"nickname"), Boolean.TRUE.equals(doc.get("enabled")), text(doc,"summary"), strings(doc.get("tags")), facts, longValue(doc.get("updatedAt"))); }
    private Map<String, Object> document(AiChatbotMemoryProfile p) { Map<String,Object> doc = new LinkedHashMap<>(); doc.put("id",p.id()); doc.put("connectionId",p.connectionId()); doc.put("channelId",p.channelId()); doc.put("userId",p.userId()); doc.put("platformUserId",p.platformUserId()); doc.put("nickname",p.nickname()); doc.put("enabled",p.enabled()); doc.put("summary",p.summary()); doc.put("tags",p.tags()); doc.put("facts",p.facts().stream().map(f -> Map.of("key",f.key(),"value",f.value(),"confidence",f.confidence(),"approved",f.approved(),"updatedAt",f.updatedAt())).toList()); doc.put("updatedAt",p.updatedAt()); return doc; }
    private void validate(AiChatbotMemoryProfile p) { if (p == null || !p.id().equals(id(p.connectionId(),p.channelId(),p.userId()))) throw new IllegalArgumentException("画像范围无效"); }
    private static String require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("标识不能为空"); return value.trim(); }
    private static String text(Map<String,Object> doc,String key) { Object value=doc.get(key); return value == null ? "" : String.valueOf(value); }
    private static long longValue(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
    private static List<String> strings(Object value) { return value instanceof List<?> rows ? rows.stream().map(String::valueOf).toList() : List.of(); }
}
