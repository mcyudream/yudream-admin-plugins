package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutomationPolicyService {
    private static final String COLLECTION = "automation-policy";
    private final PluginDocumentStore documents;

    public AutomationPolicyService(PluginDocumentStore documents) { this.documents = documents; }

    public AutomationPolicy get(String connectionId, String channelId) {
        requireId(connectionId, "connectionId"); requireId(channelId, "channelId");
        return documents.findById(COLLECTION, id(connectionId, channelId)).map(this::fromDocument)
                .orElseGet(() -> AutomationPolicy.defaults(connectionId, channelId));
    }

    public List<AutomationPolicy> list() { return documents.findAll(COLLECTION, 1, 200).stream().map(this::fromDocument).toList(); }

    public AutomationPolicy save(AutomationPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("群自动化配置不能为空");
        requireId(policy.connectionId(), "connectionId"); requireId(policy.channelId(), "channelId");
        if (policy.mediaEnabled()) {
            if (policy.mediaProviderEndpoint() == null || policy.mediaProviderEndpoint().isBlank()) {
                throw new IllegalArgumentException("启用媒体解析时必须配置解析服务地址");
            }
            try { java.net.URI.create(policy.mediaProviderEndpoint().trim()); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("媒体解析服务地址无效", exception); }
        }
        documents.save(COLLECTION, id(policy.connectionId(), policy.channelId()), toDocument(policy));
        return policy;
    }

    private AutomationPolicy fromDocument(Map<String, Object> value) {
        String connectionId = text(value.get("connectionId")), channelId = text(value.get("channelId"));
        AutomationPolicy defaults = AutomationPolicy.defaults(connectionId, channelId);
        return new AutomationPolicy(connectionId, channelId, bool(value.get("enabled"), defaults.enabled()),
                bool(value.get("mediaEnabled"), defaults.mediaEnabled()), text(value.get("mediaProviderEndpoint")),
                bool(value.get("joinVerificationEnabled"), defaults.joinVerificationEnabled()), strings(value.get("approvedAnswers")),
                strings(value.get("rejectedAnswers")), bool(value.get("aiFallbackEnabled"), false),
                bool(value.get("failClosed"), true), text(value.get("providerCode")), text(value.get("modelCode")));
    }

    private Map<String, Object> toDocument(AutomationPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("connectionId", policy.connectionId()); value.put("channelId", policy.channelId());
        value.put("enabled", policy.enabled()); value.put("mediaEnabled", policy.mediaEnabled());
        value.put("mediaProviderEndpoint", policy.mediaProviderEndpoint()); value.put("joinVerificationEnabled", policy.joinVerificationEnabled());
        value.put("approvedAnswers", safe(policy.approvedAnswers())); value.put("rejectedAnswers", safe(policy.rejectedAnswers()));
        value.put("aiFallbackEnabled", policy.aiFallbackEnabled()); value.put("failClosed", policy.failClosed());
        value.put("providerCode", policy.providerCode()); value.put("modelCode", policy.modelCode()); value.put("updatedAt", System.currentTimeMillis());
        return value;
    }

    private String id(String connectionId, String channelId) { return connectionId + ":" + channelId; }
    private void requireId(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空"); }
    private boolean bool(Object value, boolean fallback) { return value instanceof Boolean result ? result : fallback; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList() : List.of(); }
    private List<String> safe(List<String> value) { return value == null ? List.of() : value.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList(); }
}
