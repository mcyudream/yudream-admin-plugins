package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotActivityEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Persists and aggregates safe operational metadata only. */
public class AiChatbotActivityService {
    private static final String COLLECTION = "activity-event";
    private static final int SCAN_PAGE_SIZE = 100;
    private final PluginDocumentStore documents;
    public AiChatbotActivityService(PluginDocumentStore documents) { this.documents = documents; }
    public void record(String connectionId, String channelId, String platformUserId, String userId, String type, String mode, boolean success) { AiChatbotActivityEvent event = new AiChatbotActivityEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), text(connectionId), text(channelId), text(platformUserId), text(userId), required(type), text(mode), success); documents.save(COLLECTION, event.id(), document(event)); }
    public Map<String, Object> overview(Long from, Long to, String connectionId, String channelId, String type, String user) { List<AiChatbotActivityEvent> events = events(from, to, connectionId, channelId, type, user); long successful = events.stream().filter(AiChatbotActivityEvent::success).count(); return Map.of("total", events.size(), "success", successful, "failure", events.size() - successful, "users", events.stream().map(this::userKey).filter(value -> !value.isBlank()).distinct().count(), "types", counts(events, AiChatbotActivityEvent::type)); }
    public List<Map<String, Object>> timeline(Long from, Long to, String connectionId, String channelId, String type, String user, String bucket, ZoneId zone) { DateTimeFormatter formatter = "hour".equals(bucket) ? DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00XXX") : DateTimeFormatter.ISO_LOCAL_DATE; Map<String, List<AiChatbotActivityEvent>> grouped = group(events(from, to, connectionId, channelId, type, user), event -> formatter.format(Instant.ofEpochMilli(event.occurredAt()).atZone(zone).truncatedTo("hour".equals(bucket) ? ChronoUnit.HOURS : ChronoUnit.DAYS))); return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> summary("bucket", entry.getKey(), entry.getValue())).toList(); }
    public List<Map<String, Object>> heatmap(Long from, Long to, String connectionId, String channelId, String type, String user, ZoneId zone) { Map<String, List<AiChatbotActivityEvent>> grouped = group(events(from, to, connectionId, channelId, type, user), event -> { var dateTime = Instant.ofEpochMilli(event.occurredAt()).atZone(zone); return dateTime.getDayOfWeek().getValue() + ":" + dateTime.getHour(); }); return grouped.entrySet().stream().map(entry -> { String[] parts = entry.getKey().split(":"); return Map.<String, Object>of("dayOfWeek", Integer.parseInt(parts[0]), "hour", Integer.parseInt(parts[1]), "total", entry.getValue().size(), "success", entry.getValue().stream().filter(AiChatbotActivityEvent::success).count()); }).sorted(Comparator.<Map<String, Object>, Integer>comparing(row -> (Integer) row.get("dayOfWeek")).thenComparing(row -> (Integer) row.get("hour"))).toList(); }
    public List<Map<String, Object>> users(Long from, Long to, String connectionId, String channelId, String type, String user) { Map<String, List<AiChatbotActivityEvent>> grouped = group(events(from, to, connectionId, channelId, type, user), this::userKey); return grouped.entrySet().stream().filter(entry -> !entry.getKey().isBlank()).map(entry -> { AiChatbotActivityEvent sample = entry.getValue().getFirst(); return Map.<String, Object>of("userId", sample.userId(), "platformUserId", sample.platformUserId(), "total", entry.getValue().size(), "success", entry.getValue().stream().filter(AiChatbotActivityEvent::success).count(), "failure", entry.getValue().stream().filter(event -> !event.success()).count(), "lastOccurredAt", entry.getValue().stream().mapToLong(AiChatbotActivityEvent::occurredAt).max().orElse(0)); }).sorted(Comparator.<Map<String, Object>>comparingLong(row -> (Long) row.get("lastOccurredAt")).reversed()).toList(); }
    public Map<String, Object> page(Long from, Long to, String connectionId, String channelId, String type, String user, int page, int size) { List<AiChatbotActivityEvent> matched = events(from, to, connectionId, channelId, type, user); int safePage = Math.max(1, page), safeSize = Math.clamp(size, 1, 100), start = (safePage - 1) * safeSize; List<AiChatbotActivityEvent> rows = start >= matched.size() ? List.of() : matched.subList(start, Math.min(matched.size(), start + safeSize)); return Map.of("items", rows, "total", matched.size(), "page", safePage, "size", safeSize); }
    private List<AiChatbotActivityEvent> events(Long from, Long to, String connectionId, String channelId, String type, String user) { return allDocuments().stream().map(this::event).filter(event -> (from == null || event.occurredAt() >= from) && (to == null || event.occurredAt() <= to) && matches(connectionId, event.connectionId()) && matches(channelId, event.channelId()) && matches(type, event.type()) && matchesUser(user, event)).sorted(Comparator.comparingLong(AiChatbotActivityEvent::occurredAt).reversed()).toList(); }
    private List<Map<String, Object>> allDocuments() { long total = documents.count(COLLECTION); List<Map<String, Object>> result = new ArrayList<>(); for (int page = 1; result.size() < total; page++) { List<Map<String, Object>> batch = documents.findAll(COLLECTION, page, SCAN_PAGE_SIZE); if (batch.isEmpty()) break; result.addAll(batch); } return result; }
    private AiChatbotActivityEvent event(Map<String, Object> doc) { return new AiChatbotActivityEvent(text(doc.get("id")), number(doc.get("occurredAt")), text(doc.get("connectionId")), text(doc.get("channelId")), text(doc.get("platformUserId")), text(doc.get("userId")), text(doc.get("type")), text(doc.get("mode")), Boolean.TRUE.equals(doc.get("success"))); }
    private Map<String, Object> document(AiChatbotActivityEvent event) { return Map.of("id", event.id(), "occurredAt", event.occurredAt(), "connectionId", event.connectionId(), "channelId", event.channelId(), "platformUserId", event.platformUserId(), "userId", event.userId(), "type", event.type(), "mode", event.mode(), "success", event.success()); }
    private Map<String, Long> counts(List<AiChatbotActivityEvent> events, Function<AiChatbotActivityEvent, String> key) { Map<String, Long> result = new LinkedHashMap<>(); events.forEach(event -> result.merge(key.apply(event), 1L, Long::sum)); return result; }
    private Map<String, List<AiChatbotActivityEvent>> group(List<AiChatbotActivityEvent> events, Function<AiChatbotActivityEvent, String> key) { Map<String, List<AiChatbotActivityEvent>> result = new LinkedHashMap<>(); events.forEach(event -> result.computeIfAbsent(key.apply(event), ignored -> new ArrayList<>()).add(event)); return result; }
    private Map<String, Object> summary(String field, String value, List<AiChatbotActivityEvent> events) { long successful = events.stream().filter(AiChatbotActivityEvent::success).count(); return Map.of(field, value, "total", events.size(), "success", successful, "failure", events.size() - successful); }
    private String userKey(AiChatbotActivityEvent event) { return event.userId().isBlank() ? event.platformUserId() : event.userId(); }
    private boolean matches(String expected, String actual) { return expected == null || expected.isBlank() || expected.equals(actual); }
    private boolean matchesUser(String user, AiChatbotActivityEvent event) { return user == null || user.isBlank() || user.equals(event.userId()) || user.equals(event.platformUserId()); }
    private String required(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("活动类型不能为空"); return value.trim(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
}
