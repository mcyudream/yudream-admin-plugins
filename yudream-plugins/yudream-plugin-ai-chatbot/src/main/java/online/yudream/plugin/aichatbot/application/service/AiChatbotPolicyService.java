package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AiChatbotPolicyService {
    private final PluginDocumentStore documents;
    public AiChatbotPolicyService(PluginDocumentStore documents) { this.documents = documents; }
    public AiChatbotGroupPolicy get(String connectionId, String channelId) {
        requireId(connectionId, "connectionId"); requireId(channelId, "channelId");
        return documents.findById("group-policy", id(connectionId, channelId)).map(this::fromDocument)
                .orElseGet(() -> AiChatbotGroupPolicy.defaults(connectionId, channelId));
    }
    public List<AiChatbotGroupPolicy> list() { return documents.findAll("group-policy", 1, 200).stream().map(this::fromDocument).toList(); }
    public AiChatbotGroupPolicy save(AiChatbotGroupPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("群聊配置不能为空"); validate(policy);
        documents.save("group-policy", id(policy.connectionId(), policy.channelId()), toDocument(policy)); return policy;
    }
    public boolean allowReply(AiChatbotGroupPolicy policy, long now, boolean explicitMention) {
        if (!policy.enabled() || inQuietHours(policy)) return false;
        Map<String, Object> state = documents.findById("reply-state", id(policy.connectionId(), policy.channelId())).orElse(Map.of());
        long last = longValue(state.get("lastReplyAt"), 0), hour = now / 3_600_000L * 3_600_000L;
        return (explicitMention || now - last >= policy.cooldownSeconds() * 1000L) && (longValue(state.get("hourStart"), -1) != hour || longValue(state.get("replies"), 0) < policy.hourlyReplyLimit());
    }
    public void recordReply(AiChatbotGroupPolicy policy, long now) {
        String id = id(policy.connectionId(), policy.channelId()); Map<String, Object> previous = documents.findById("reply-state", id).orElse(Map.of());
        long hour = now / 3_600_000L * 3_600_000L, replies = longValue(previous.get("hourStart"), -1) == hour ? longValue(previous.get("replies"), 0) + 1 : 1;
        documents.save("reply-state", id, Map.of("lastReplyAt", now, "hourStart", hour, "replies", replies));
    }
    private AiChatbotGroupPolicy fromDocument(Map<String, Object> doc) {
        String connectionId = stringValue(doc.get("connectionId")), channelId = stringValue(doc.get("channelId")); AiChatbotGroupPolicy d = AiChatbotGroupPolicy.defaults(connectionId, channelId);
        String prompt = stringValue(doc.get("systemPrompt"));
        return new AiChatbotGroupPolicy(connectionId, channelId, boolValue(doc.get("enabled"), d.enabled()), doubleValue(doc.get("randomProbability"), d.randomProbability()),
                intValue(doc.get("groupContextLimit"), d.groupContextLimit()), intValue(doc.get("personalContextLimit"), d.personalContextLimit()), intValue(doc.get("contextExpansionLimit"), d.contextExpansionLimit()),
                intValue(doc.get("cooldownSeconds"), d.cooldownSeconds()), intValue(doc.get("hourlyReplyLimit"), d.hourlyReplyLimit()), nullable(doc.get("quietHoursStart")), nullable(doc.get("quietHoursEnd")), prompt.isBlank() ? d.systemPrompt() : prompt, stringValue(doc.get("persona")), stringList(doc.get("enabledToolNames")), boolValue(doc.get("randomToolCallingEnabled"), false), boolValue(doc.get("longTermMemoryEnabled"), false), intValue(doc.get("semanticMemoryTopK"), d.semanticMemoryTopK()), stringValue(doc.get("agentCode")), stringValue(doc.get("providerCode")), stringValue(doc.get("modelCode")));
    }
    private Map<String, Object> toDocument(AiChatbotGroupPolicy p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("connectionId", p.connectionId()); v.put("channelId", p.channelId()); v.put("enabled", p.enabled()); v.put("randomProbability", p.randomProbability());
        v.put("groupContextLimit", p.groupContextLimit()); v.put("personalContextLimit", p.personalContextLimit()); v.put("contextExpansionLimit", p.contextExpansionLimit());
        v.put("cooldownSeconds", p.cooldownSeconds()); v.put("hourlyReplyLimit", p.hourlyReplyLimit());
        if (p.quietHoursStart() != null) v.put("quietHoursStart", p.quietHoursStart());
        if (p.quietHoursEnd() != null) v.put("quietHoursEnd", p.quietHoursEnd());
        v.put("systemPrompt", p.systemPrompt()); v.put("persona", p.persona()); v.put("enabledToolNames", p.enabledToolNames()); v.put("randomToolCallingEnabled", p.randomToolCallingEnabled()); v.put("longTermMemoryEnabled", p.longTermMemoryEnabled()); v.put("semanticMemoryTopK", p.semanticMemoryTopK()); v.put("agentCode", p.agentCode());
        if (!p.providerCode().isBlank()) v.put("providerCode", p.providerCode());
        if (!p.modelCode().isBlank()) v.put("modelCode", p.modelCode());
        v.put("updatedAt", System.currentTimeMillis()); return v;
    }
    private void validate(AiChatbotGroupPolicy p) {
        requireId(p.connectionId(), "connectionId"); requireId(p.channelId(), "channelId");
        if (p.randomProbability() < 0 || p.randomProbability() > 1) throw new IllegalArgumentException("随机回复概率必须在 0 到 1 之间");
        range(p.groupContextLimit(), "群聊上下文条数", 32); range(p.personalContextLimit(), "个人上下文条数", 32); range(p.contextExpansionLimit(), "上下文扩展条数", 32); range(p.cooldownSeconds(), "冷却时间", 3600); range(p.hourlyReplyLimit(), "每小时回复上限", 1000);
        time(p.quietHoursStart()); time(p.quietHoursEnd());
    }
    private boolean inQuietHours(AiChatbotGroupPolicy p) { if (p.quietHoursStart() == null || p.quietHoursEnd() == null) return false; LocalTime s = LocalTime.parse(p.quietHoursStart()), e = LocalTime.parse(p.quietHoursEnd()), n = LocalTime.now(); return s.equals(e) || (s.isBefore(e) ? !n.isBefore(s) && n.isBefore(e) : !n.isBefore(s) || n.isBefore(e)); }
    private String id(String connectionId, String channelId) { return connectionId + ":" + channelId; }
    private void requireId(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空"); }
    private void range(int value, String name, int max) { if (value < 1 || value > max) throw new IllegalArgumentException(name + "必须在 1 到 " + max + " 之间"); }
    private void time(String value) { if (value != null) LocalTime.parse(value); }
    private String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private String nullable(Object value) { String v = stringValue(value).trim(); return v.isBlank() ? null : v; }
    private boolean boolValue(Object value, boolean fallback) { return value instanceof Boolean b ? b : fallback; }
    private int intValue(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private long longValue(Object value, long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
    private double doubleValue(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private List<String> stringList(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList() : List.of(); }
}
