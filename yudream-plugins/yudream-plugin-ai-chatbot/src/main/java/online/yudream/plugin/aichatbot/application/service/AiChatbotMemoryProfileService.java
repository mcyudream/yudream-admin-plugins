package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfile;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfilePage;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileAnalysis;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileObservation;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotMemoryProfileUpdateRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AiChatbotMemoryProfileService {
    private static final String COLLECTION = "memory-profile";
    private static final String OBSERVATION_COLLECTION = "profile-observation";
    private static final int MAX_SUMMARY_LENGTH = 1000, MAX_PERSONALITY_LENGTH = 1000, MAX_INTERACTION_LENGTH = 1000, MAX_NICKNAME_LENGTH = 100, MAX_TAGS = 20, MAX_TAG_LENGTH = 50, MAX_FACTS = 30, MAX_FACT_KEY_LENGTH = 50, MAX_FACT_VALUE_LENGTH = 500;
    private static final int MAX_OBSERVATIONS = 40, MAX_OBSERVATION_LENGTH = 120;
    private static final Set<String> ADMIN_FACT_KEYS = Set.of("preference", "interest", "identity", "note");
    private static final Set<String> AI_FACT_KEYS = Set.of("habit", "topic", "emotion");
    private static final Set<String> STORED_FACT_KEYS = union(ADMIN_FACT_KEYS, AI_FACT_KEYS);
    private static final String RECENT_MESSAGE = "recent_message";
    private final PluginDocumentStore documents;
    public AiChatbotMemoryProfileService(PluginDocumentStore documents) { this.documents = documents; }
    public AiChatbotMemoryProfilePage page(int page, int size) { return new AiChatbotMemoryProfilePage(documents.findAll(COLLECTION, Math.max(1, page), Math.clamp(size, 1, 100)).stream().map(this::profile).toList(), documents.count(COLLECTION)); }
    public AiChatbotMemoryProfile get(String id) { return documents.findById(COLLECTION, require(id)).map(this::profile).orElseThrow(() -> new IllegalArgumentException("画像不存在")); }
    public AiChatbotMemoryProfile update(AiChatbotMemoryProfileUpdateRequest value) {
        if (value == null) throw new IllegalArgumentException("画像无效");
        validateEditable(value.summary(), value.tags(), value.facts());
        AiChatbotMemoryProfile current = get(value.id());
        List<AiChatbotMemoryFact> facts = new ArrayList<>(value.facts());
        current.facts().stream().filter(fact -> RECENT_MESSAGE.equals(fact.key())).forEach(facts::add);
        AiChatbotMemoryProfile updated = new AiChatbotMemoryProfile(current.id(), current.connectionId(), current.channelId(), current.userId(), current.platformUserId(), current.nickname(), current.avatar(), value.enabled(), value.summary(), current.personality(), current.interactionStyle(), List.copyOf(value.tags()), facts, current.observedMessageCount(), current.replyTriggeredCount(), current.replyCompletedCount(), current.replyFailedCount(), current.lastActivityAt(), current.lastAnalyzedAt(), System.currentTimeMillis());
        saveInternal(updated);
        return updated;
    }
    /** Merges a parsed AI analysis into the profile; approved administrator facts are never overwritten or removed. */
    public AiChatbotMemoryProfile applyAnalysis(String id, AiChatbotProfileAnalysis analysis) {
        if (analysis == null) throw new IllegalArgumentException("分析结果无效");
        AiChatbotMemoryProfile current = get(id);
        long now = System.currentTimeMillis();
        // 沉淀式合并：管理员已确认的事实与 recent_message 原始发言永远保留；AI 新产出的事实
        // 对同 key 未确认旧事实做修正替换，对新 key 追加，避免持续分析时数据丢失或错误固化
        List<AiChatbotMemoryFact> facts = new ArrayList<>(current.facts().stream().filter(fact -> fact.approved() || RECENT_MESSAGE.equals(fact.key())).toList());
        Set<String> approvedKeys = new HashSet<>();
        facts.stream().filter(AiChatbotMemoryFact::approved).forEach(fact -> approvedKeys.add(fact.key()));
        for (AiChatbotMemoryFact fact : analysis.facts()) {
            if (fact == null || !validFact(fact) || !STORED_FACT_KEYS.contains(fact.key()) || approvedKeys.contains(fact.key())) continue;
            int existingIndex = -1;
            for (int i = 0; i < facts.size(); i++) {
                if (facts.get(i).key().equals(fact.key()) && !RECENT_MESSAGE.equals(facts.get(i).key())) { existingIndex = i; break; }
            }
            if (existingIndex >= 0) {
                facts.set(existingIndex, new AiChatbotMemoryFact(fact.key(), fact.value(), Math.clamp(fact.confidence(), 0d, 1d), false, now));
                continue;
            }
            if (facts.size() >= MAX_FACTS) break;
            facts.add(new AiChatbotMemoryFact(fact.key(), fact.value(), Math.clamp(fact.confidence(), 0d, 1d), false, now));
        }
        // 标签取并集：AI 新标签追加在后，人工维护的标签不因分析丢失
        List<String> tags = new ArrayList<>(current.tags());
        for (String tag : analysis.tags()) {
            if (!tags.contains(tag)) tags.add(tag);
            if (tags.size() >= MAX_TAGS) break;
        }
        AiChatbotMemoryProfile updated = new AiChatbotMemoryProfile(current.id(), current.connectionId(), current.channelId(), current.userId(), current.platformUserId(), current.nickname(), current.avatar(), current.enabled(),
                analysis.summary().isBlank() ? current.summary() : analysis.summary(),
                analysis.personality().isBlank() ? current.personality() : analysis.personality(),
                analysis.interactionStyle().isBlank() ? current.interactionStyle() : analysis.interactionStyle(),
                List.copyOf(tags),
                List.copyOf(facts), current.observedMessageCount(), current.replyTriggeredCount(), current.replyCompletedCount(), current.replyFailedCount(), current.lastActivityAt(), now, now);
        saveInternal(updated);
        return updated;
    }
    /** Returns bounded user-word observations, newest first; never contains AI output. */
    public List<AiChatbotProfileObservation> observations(String id) { List<AiChatbotProfileObservation> rows = new ArrayList<>(observationRows(require(id))); return rows.reversed(); }
    public AiChatbotMemoryProfile enabled(String id, boolean enabled) { AiChatbotMemoryProfile current = get(id); AiChatbotMemoryProfile updated = copy(current, enabled, current.observedMessageCount(), current.replyTriggeredCount(), current.replyCompletedCount(), current.replyFailedCount(), current.lastActivityAt()); saveInternal(updated); return updated; }
    public void delete(String id) { documents.delete(COLLECTION, require(id)); documents.delete(OBSERVATION_COLLECTION, require(id)); }
    public AiChatbotMemoryProfile find(String connectionId, String channelId, String userId) { return documents.findById(COLLECTION, id(connectionId, channelId, userId)).map(this::profile).orElse(null); }
    /** Persists the existing profile-only recent-message fact plus a bounded truncated observation of the user's own words. */
    public void observe(String connectionId, String channelId, String userId, String platformUserId, String nickname, String avatar, String content) { updateActivity(connectionId, channelId, userId, platformUserId, nickname, avatar, content, 1, 0, 0, 0); }
    public void recordReply(String connectionId, String channelId, String userId, String platformUserId, String nickname, String avatar, String outcome) { if (userId == null || userId.isBlank()) return; updateActivity(connectionId, channelId, userId, platformUserId, nickname, avatar, null, 0, 1, "COMPLETED".equals(outcome) ? 1 : 0, "FAILED".equals(outcome) ? 1 : 0); }
    public static String id(String connectionId, String channelId, String userId) { return require(connectionId) + ":" + require(channelId) + ":" + require(userId); }

    private void updateActivity(String connectionId, String channelId, String userId, String platformUserId, String nickname, String avatar, String content, long observed, long triggered, long completed, long failed) {
        if (userId == null || userId.isBlank()) return;
        AiChatbotMemoryProfile current = find(connectionId, channelId, userId); long now = System.currentTimeMillis();
        List<AiChatbotMemoryFact> facts = current == null ? new ArrayList<>() : new ArrayList<>(current.facts());
        boolean hasContent = content != null && !content.isBlank();
        if (hasContent) { facts.removeIf(fact -> RECENT_MESSAGE.equals(fact.key()) && !fact.approved()); facts.add(new AiChatbotMemoryFact(RECENT_MESSAGE, content.substring(0, Math.min(content.length(), 300)), 0.2d, false, now)); }
        String safeAvatar = safe(avatar, 500);
        if (current == null) saveInternal(new AiChatbotMemoryProfile(id(connectionId, channelId, userId), require(connectionId), require(channelId), require(userId), safe(platformUserId, 100), safe(nickname, MAX_NICKNAME_LENGTH), safeAvatar, true, "", "", "", List.of(), facts, observed, triggered, completed, failed, now, 0, now));
        else saveInternal(new AiChatbotMemoryProfile(current.id(), current.connectionId(), current.channelId(), current.userId(), current.platformUserId(), current.nickname(), safeAvatar.isBlank() ? current.avatar() : safeAvatar, current.enabled(), current.summary(), current.personality(), current.interactionStyle(), current.tags(), facts, current.observedMessageCount() + observed, current.replyTriggeredCount() + triggered, current.replyCompletedCount() + completed, current.replyFailedCount() + failed, now, current.lastAnalyzedAt(), System.currentTimeMillis()));
        if (hasContent) recordObservation(id(connectionId, channelId, userId), content, now);
    }
    private void recordObservation(String profileId, String content, long occurredAt) {
        List<AiChatbotProfileObservation> rows = new ArrayList<>(observationRows(profileId));
        rows.add(new AiChatbotProfileObservation(safe(content.replaceAll("\\s+", " ").trim(), MAX_OBSERVATION_LENGTH), occurredAt));
        if (rows.size() > MAX_OBSERVATIONS) rows = new ArrayList<>(rows.subList(rows.size() - MAX_OBSERVATIONS, rows.size()));
        documents.save(OBSERVATION_COLLECTION, profileId, Map.of("id", profileId, "observations", rows.stream().map(row -> Map.of("content", row.content(), "occurredAt", row.occurredAt())).toList()));
    }
    private List<AiChatbotProfileObservation> observationRows(String profileId) { Object raw = documents.findById(OBSERVATION_COLLECTION, profileId).map(doc -> doc.get("observations")).orElse(List.of()); List<AiChatbotProfileObservation> rows = new ArrayList<>(); if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> map) rows.add(new AiChatbotProfileObservation(String.valueOf(map.get("content")), longValue(map.get("occurredAt")))); return rows; }
    private void saveInternal(AiChatbotMemoryProfile value) { validateStored(value); documents.save(COLLECTION, value.id(), document(value)); }
    private AiChatbotMemoryProfile copy(AiChatbotMemoryProfile current, boolean enabled, long observed, long triggered, long completed, long failed, long lastActivityAt) { return new AiChatbotMemoryProfile(current.id(), current.connectionId(), current.channelId(), current.userId(), current.platformUserId(), current.nickname(), current.avatar(), enabled, current.summary(), current.personality(), current.interactionStyle(), current.tags(), current.facts(), observed, triggered, completed, failed, lastActivityAt, current.lastAnalyzedAt(), System.currentTimeMillis()); }
    private AiChatbotMemoryProfile profile(Map<String, Object> doc) { String id = text(doc, "id"); List<AiChatbotMemoryFact> facts = new ArrayList<>(); Object rawFacts = doc.get("facts"); if (rawFacts instanceof List<?> rows) for (Object row : rows) if (row instanceof Map<?, ?> map) facts.add(new AiChatbotMemoryFact(String.valueOf(map.get("key")), String.valueOf(map.get("value")), number(map.get("confidence")), Boolean.TRUE.equals(map.get("approved")), longValue(map.get("updatedAt")))); return new AiChatbotMemoryProfile(id, text(doc,"connectionId"), text(doc,"channelId"), text(doc,"userId"), text(doc,"platformUserId"), text(doc,"nickname"), text(doc,"avatar"), Boolean.TRUE.equals(doc.get("enabled")), text(doc,"summary"), text(doc,"personality"), text(doc,"interactionStyle"), strings(doc.get("tags")), facts, longValue(doc.get("observedMessageCount")), longValue(doc.get("replyTriggeredCount")), longValue(doc.get("replyCompletedCount")), longValue(doc.get("replyFailedCount")), longValue(doc.get("lastActivityAt")), longValue(doc.get("lastAnalyzedAt")), longValue(doc.get("updatedAt"))); }
    private Map<String, Object> document(AiChatbotMemoryProfile p) { Map<String,Object> doc = new LinkedHashMap<>(); doc.put("id",p.id()); doc.put("connectionId",p.connectionId()); doc.put("channelId",p.channelId()); doc.put("userId",p.userId()); doc.put("platformUserId",p.platformUserId()); doc.put("nickname",p.nickname()); doc.put("avatar",p.avatar()); doc.put("enabled",p.enabled()); doc.put("summary",p.summary()); doc.put("personality",p.personality()); doc.put("interactionStyle",p.interactionStyle()); doc.put("tags",p.tags()); doc.put("facts",p.facts().stream().map(f -> Map.of("key",f.key(),"value",f.value(),"confidence",f.confidence(),"approved",f.approved(),"updatedAt",f.updatedAt())).toList()); doc.put("observedMessageCount",p.observedMessageCount()); doc.put("replyTriggeredCount",p.replyTriggeredCount()); doc.put("replyCompletedCount",p.replyCompletedCount()); doc.put("replyFailedCount",p.replyFailedCount()); doc.put("lastActivityAt",p.lastActivityAt()); doc.put("lastAnalyzedAt",p.lastAnalyzedAt()); doc.put("updatedAt",p.updatedAt()); return doc; }
    private void validateStored(AiChatbotMemoryProfile p) { if (p == null || !p.id().equals(id(p.connectionId(),p.channelId(),p.userId()))) throw new IllegalArgumentException("画像范围无效"); validText(p.platformUserId(), 100, "平台用户标识"); validText(p.nickname(), MAX_NICKNAME_LENGTH, "昵称"); validText(p.avatar(), 500, "头像"); validText(p.summary(), MAX_SUMMARY_LENGTH, "摘要"); validText(p.personality(), MAX_PERSONALITY_LENGTH, "人格分析"); validText(p.interactionStyle(), MAX_INTERACTION_LENGTH, "互动分析"); validateTags(p.tags()); if (p.facts() == null || p.facts().size() > MAX_FACTS || p.facts().stream().anyMatch(fact -> !validFact(fact) || (!STORED_FACT_KEYS.contains(fact.key()) && !RECENT_MESSAGE.equals(fact.key())))) throw new IllegalArgumentException("事实无效"); if (p.observedMessageCount() < 0 || p.replyTriggeredCount() < 0 || p.replyCompletedCount() < 0 || p.replyFailedCount() < 0 || p.lastActivityAt() < 0 || p.lastAnalyzedAt() < 0 || p.updatedAt() < 0) throw new IllegalArgumentException("行为计数无效"); }
    private void validateEditable(String summary, List<String> tags, List<AiChatbotMemoryFact> facts) { validText(summary, MAX_SUMMARY_LENGTH, "摘要"); validateTags(tags); if (facts == null || facts.size() > MAX_FACTS || facts.stream().anyMatch(fact -> !validFact(fact) || !STORED_FACT_KEYS.contains(fact.key()))) throw new IllegalArgumentException("事实无效"); }
    private void validateTags(List<String> tags) { if (tags == null || tags.size() > MAX_TAGS || tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH)) throw new IllegalArgumentException("标签无效"); }
    private boolean validFact(AiChatbotMemoryFact fact) { return fact != null && fact.value() != null && !fact.value().isBlank() && fact.key() != null && fact.key().length() <= MAX_FACT_KEY_LENGTH && fact.value().length() <= MAX_FACT_VALUE_LENGTH && fact.confidence() >= 0 && fact.confidence() <= 1 && fact.updatedAt() >= 0; }
    private static Set<String> union(Set<String> left, Set<String> right) { Set<String> result = new HashSet<>(left); result.addAll(right); return Set.copyOf(result); }
    private static String require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("标识不能为空"); return value.trim(); }
    private static void validText(String value, int max, String field) { if (value == null || value.length() > max) throw new IllegalArgumentException(field + "无效"); }
    private static String safe(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
    private static String text(Map<String,Object> doc,String key) { Object value=doc.get(key); return value == null ? "" : String.valueOf(value); }
    private static long longValue(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
    private static List<String> strings(Object value) { return value instanceof List<?> rows ? rows.stream().map(String::valueOf).toList() : List.of(); }
}
