package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicyOverride;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AutomationPolicyService {
    private static final String LEGACY_COLLECTION = "automation-policy";
    private static final String DEFAULT_COLLECTION = "automation-policy-default";
    private static final String OVERRIDE_COLLECTION = "automation-policy-override";
    private static final String OVERRIDE_COUNT_COLLECTION = "automation-policy-override-count";
    private final PluginDocumentStore documents;

    public AutomationPolicyService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /**
     * Kept for event handlers and existing callers. It now returns the effective hierarchy result.
     */
    public AutomationPolicy get(String connectionId, String channelId) {
        return resolve(connectionId, channelId);
    }

    public AutomationPolicy resolve(String connectionId, String channelId) {
        requireId(connectionId, "connectionId");
        requireId(channelId, "channelId");
        AutomationPolicy defaults = getDefaults(connectionId);
        Optional<AutomationPolicyOverride> override = readOverride(connectionId, channelId);
        return merge(defaults, override.orElse(null), channelId);
    }

    public AutomationPolicy getDefaults(String connectionId) {
        requireId(connectionId, "connectionId");
        return documents.findById(DEFAULT_COLLECTION, connectionId)
                .map(document -> completePolicy(document, connectionId, ""))
                .orElseGet(() -> AutomationPolicy.connectionDefaults(connectionId));
    }

    public AutomationPolicy saveDefaults(AutomationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Automation defaults cannot be null");
        }
        requireId(policy.connectionId(), "connectionId");
        AutomationPolicy normalized = new AutomationPolicy(policy.connectionId(), "", policy.enabled(),
                policy.mediaEnabled(), text(policy.mediaProviderEndpoint()), policy.joinVerificationEnabled(),
                safe(policy.approvedAnswers()), safe(policy.rejectedAnswers()), policy.aiFallbackEnabled(),
                policy.failClosed(), text(policy.providerCode()), text(policy.modelCode()));
        validateEffective(normalized);
        documents.save(DEFAULT_COLLECTION, normalized.connectionId(), toDocument(normalized));
        return normalized;
    }

    public Optional<AutomationPolicyOverride> getOverride(String connectionId, String channelId) {
        requireId(connectionId, "connectionId");
        requireId(channelId, "channelId");
        return readOverride(connectionId, channelId);
    }

    public AutomationPolicyOverride saveOverride(AutomationPolicyOverride override) {
        if (override == null) {
            throw new IllegalArgumentException("Automation group override cannot be null");
        }
        requireId(override.connectionId(), "connectionId");
        requireId(override.channelId(), "channelId");
        AutomationPolicyOverride normalized = normalize(override);
        validateEffective(merge(getDefaults(normalized.connectionId()), normalized, normalized.channelId()));
        String id = id(normalized.connectionId(), normalized.channelId());
        boolean existing = documents.findById(OVERRIDE_COLLECTION, id).isPresent();
        documents.save(OVERRIDE_COLLECTION, id, toDocument(normalized));
        if (!existing) {
            adjustOverrideCount(normalized.connectionId(), 1);
        }
        return normalized;
    }

    public List<AutomationPolicyOverride> pageOverrides(String connectionId, int page, int size) {
        requireId(connectionId, "connectionId");
        return documents.findByField(OVERRIDE_COLLECTION, "connectionId", connectionId, positive(page), boundedSize(size))
                .stream().map(this::overrideFromDocument).toList();
    }

    public long countOverrides(String connectionId) {
        requireId(connectionId, "connectionId");
        return countDocument(connectionId);
    }

    public void deleteOverride(String connectionId, String channelId) {
        requireId(connectionId, "connectionId");
        requireId(channelId, "channelId");
        String id = id(connectionId, channelId);
        if (documents.findById(OVERRIDE_COLLECTION, id).isPresent()) {
            documents.delete(OVERRIDE_COLLECTION, id);
            adjustOverrideCount(connectionId, -1);
        }
        // A legacy document is itself a complete group override. Removing it makes the group inherit defaults.
        documents.delete(LEGACY_COLLECTION, id);
    }

    /**
     * Moves the previous complete group-policy records into the sparse override collection once at plugin startup.
     * Regular management requests then remain backed by the document store's paginated field query.
     */
    public void migrateLegacyPolicies() {
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(LEGACY_COLLECTION, 1, 100);
            if (batch.isEmpty()) {
                return;
            }
            for (Map<String, Object> document : batch) {
                AutomationPolicy policy = completePolicy(document, text(document.get("connectionId")), text(document.get("channelId")));
                String id = id(policy.connectionId(), policy.channelId());
                if (documents.findById(OVERRIDE_COLLECTION, id).isEmpty()) {
                    documents.save(OVERRIDE_COLLECTION, id, toDocument(AutomationPolicyOverride.complete(policy)));
                    adjustOverrideCount(policy.connectionId(), 1);
                }
                documents.delete(LEGACY_COLLECTION, id);
            }
        }
    }

    /**
     * Existing management callers save full policies. Persisting them as complete sparse overrides retains
     * their previous group-specific behaviour while moving new writes to the hierarchy model.
     */
    public AutomationPolicy save(AutomationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Automation policy cannot be null");
        }
        saveOverride(AutomationPolicyOverride.complete(policy));
        return resolve(policy.connectionId(), policy.channelId());
    }

    public List<AutomationPolicy> list() {
        return mergedOverrides(null).stream()
                .map(override -> resolve(override.connectionId(), override.channelId()))
                .toList();
    }

    private Optional<AutomationPolicyOverride> readOverride(String connectionId, String channelId) {
        String id = id(connectionId, channelId);
        Optional<AutomationPolicyOverride> current = documents.findById(OVERRIDE_COLLECTION, id)
                .map(this::overrideFromDocument);
        if (current.isPresent()) {
            return current;
        }
        // automation-policy was the original complete per-group policy storage.
        return documents.findById(LEGACY_COLLECTION, id)
                .map(document -> AutomationPolicyOverride.complete(completePolicy(document, connectionId, channelId)));
    }

    private AutomationPolicy merge(AutomationPolicy defaults, AutomationPolicyOverride override, String channelId) {
        if (override == null) {
            return withChannel(defaults, channelId);
        }
        return new AutomationPolicy(defaults.connectionId(), channelId,
                value(override.enabled(), defaults.enabled()),
                value(override.mediaEnabled(), defaults.mediaEnabled()),
                value(override.mediaProviderEndpoint(), defaults.mediaProviderEndpoint()),
                value(override.joinVerificationEnabled(), defaults.joinVerificationEnabled()),
                value(override.approvedAnswers(), defaults.approvedAnswers()),
                value(override.rejectedAnswers(), defaults.rejectedAnswers()),
                value(override.aiFallbackEnabled(), defaults.aiFallbackEnabled()),
                value(override.failClosed(), defaults.failClosed()),
                value(override.providerCode(), defaults.providerCode()),
                value(override.modelCode(), defaults.modelCode()));
    }

    private List<AutomationPolicyOverride> mergedOverrides(String connectionId) {
        Map<String, AutomationPolicyOverride> overrides = new LinkedHashMap<>();
        for (Map<String, Object> document : allDocuments(LEGACY_COLLECTION)) {
            AutomationPolicy policy = completePolicy(document, text(document.get("connectionId")), text(document.get("channelId")));
            if (connectionId == null || connectionId.equals(policy.connectionId())) {
                overrides.put(id(policy.connectionId(), policy.channelId()), AutomationPolicyOverride.complete(policy));
            }
        }
        for (Map<String, Object> document : allDocuments(OVERRIDE_COLLECTION)) {
            AutomationPolicyOverride override = overrideFromDocument(document);
            if (connectionId == null || connectionId.equals(override.connectionId())) {
                overrides.put(id(override.connectionId(), override.channelId()), override);
            }
        }
        return List.copyOf(overrides.values());
    }

    private List<Map<String, Object>> allDocuments(String collection) {
        long total = documents.count(collection);
        if (total == 0) {
            return List.of();
        }
        int pageSize = 100;
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (int page = 1; result.size() < total; page++) {
            List<Map<String, Object>> batch = documents.findAll(collection, page, pageSize);
            if (batch.isEmpty()) {
                break;
            }
            result.addAll(batch);
        }
        return result;
    }

    private long countDocument(String connectionId) {
        return documents.findById(OVERRIDE_COUNT_COLLECTION, connectionId)
                .map(document -> document.get("count"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .orElse(0L);
    }

    private void adjustOverrideCount(String connectionId, int delta) {
        long next = Math.max(0L, countDocument(connectionId) + delta);
        documents.save(OVERRIDE_COUNT_COLLECTION, connectionId, Map.of("connectionId", connectionId, "count", next));
    }

    private AutomationPolicy completePolicy(Map<String, Object> document, String fallbackConnectionId, String fallbackChannelId) {
        String connectionId = textOr(document.get("connectionId"), fallbackConnectionId);
        String channelId = textOr(document.get("channelId"), fallbackChannelId);
        AutomationPolicy defaults = AutomationPolicy.defaults(connectionId, channelId);
        return new AutomationPolicy(connectionId, channelId, bool(document.get("enabled"), defaults.enabled()),
                bool(document.get("mediaEnabled"), defaults.mediaEnabled()), text(document.get("mediaProviderEndpoint")),
                bool(document.get("joinVerificationEnabled"), defaults.joinVerificationEnabled()), strings(document.get("approvedAnswers")),
                strings(document.get("rejectedAnswers")), bool(document.get("aiFallbackEnabled"), defaults.aiFallbackEnabled()),
                bool(document.get("failClosed"), defaults.failClosed()), text(document.get("providerCode")), text(document.get("modelCode")));
    }

    private AutomationPolicyOverride overrideFromDocument(Map<String, Object> document) {
        return new AutomationPolicyOverride(text(document.get("connectionId")), text(document.get("channelId")),
                nullableBoolean(document.get("enabled")), nullableBoolean(document.get("mediaEnabled")),
                nullableText(document.get("mediaProviderEndpoint")), nullableBoolean(document.get("joinVerificationEnabled")),
                nullableStrings(document, "approvedAnswers"), nullableStrings(document, "rejectedAnswers"),
                nullableBoolean(document.get("aiFallbackEnabled")), nullableBoolean(document.get("failClosed")),
                nullableText(document.get("providerCode")), nullableText(document.get("modelCode")));
    }

    private AutomationPolicyOverride normalize(AutomationPolicyOverride policy) {
        return new AutomationPolicyOverride(text(policy.connectionId()), text(policy.channelId()), policy.enabled(),
                policy.mediaEnabled(), nullableText(policy.mediaProviderEndpoint()), policy.joinVerificationEnabled(),
                nullableList(policy.approvedAnswers()), nullableList(policy.rejectedAnswers()), policy.aiFallbackEnabled(),
                policy.failClosed(), nullableText(policy.providerCode()), nullableText(policy.modelCode()));
    }

    private Map<String, Object> toDocument(AutomationPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("connectionId", policy.connectionId());
        value.put("channelId", policy.channelId());
        value.put("enabled", policy.enabled());
        value.put("mediaEnabled", policy.mediaEnabled());
        value.put("mediaProviderEndpoint", policy.mediaProviderEndpoint());
        value.put("joinVerificationEnabled", policy.joinVerificationEnabled());
        value.put("approvedAnswers", safe(policy.approvedAnswers()));
        value.put("rejectedAnswers", safe(policy.rejectedAnswers()));
        value.put("aiFallbackEnabled", policy.aiFallbackEnabled());
        value.put("failClosed", policy.failClosed());
        value.put("providerCode", policy.providerCode());
        value.put("modelCode", policy.modelCode());
        value.put("updatedAt", System.currentTimeMillis());
        return value;
    }

    private Map<String, Object> toDocument(AutomationPolicyOverride policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("connectionId", policy.connectionId());
        value.put("channelId", policy.channelId());
        put(value, "enabled", policy.enabled());
        put(value, "mediaEnabled", policy.mediaEnabled());
        put(value, "mediaProviderEndpoint", policy.mediaProviderEndpoint());
        put(value, "joinVerificationEnabled", policy.joinVerificationEnabled());
        put(value, "approvedAnswers", policy.approvedAnswers());
        put(value, "rejectedAnswers", policy.rejectedAnswers());
        put(value, "aiFallbackEnabled", policy.aiFallbackEnabled());
        put(value, "failClosed", policy.failClosed());
        put(value, "providerCode", policy.providerCode());
        put(value, "modelCode", policy.modelCode());
        value.put("updatedAt", System.currentTimeMillis());
        return value;
    }

    private void validateEffective(AutomationPolicy policy) {
        if (!policy.mediaEnabled() || policy.mediaProviderEndpoint() == null || policy.mediaProviderEndpoint().isBlank()) {
            return;
        }
        try {
            URI.create(policy.mediaProviderEndpoint().trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Media provider endpoint is invalid", exception);
        }
    }

    private AutomationPolicy withChannel(AutomationPolicy policy, String channelId) {
        return new AutomationPolicy(policy.connectionId(), channelId, policy.enabled(), policy.mediaEnabled(),
                policy.mediaProviderEndpoint(), policy.joinVerificationEnabled(), policy.approvedAnswers(),
                policy.rejectedAnswers(), policy.aiFallbackEnabled(), policy.failClosed(), policy.providerCode(),
                policy.modelCode());
    }

    private <T> T value(T override, T fallback) {
        return override == null ? fallback : override;
    }

    private void put(Map<String, Object> document, String key, Object value) {
        if (value != null) {
            document.put(key, value instanceof List<?> list ? safe(list) : value);
        }
    }

    private String id(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    private void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private int positive(int value) {
        return Math.max(value, 1);
    }

    private int boundedSize(int value) {
        return Math.clamp(value, 1, 100);
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean result ? result : fallback;
    }

    private Boolean nullableBoolean(Object value) {
        return value instanceof Boolean result ? result : null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private String nullableText(Object value) {
        return value == null ? null : text(value);
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> list ? safe(list) : List.of();
    }

    private List<String> nullableStrings(Map<String, Object> document, String key) {
        return document.containsKey(key) ? strings(document.get(key)) : null;
    }

    private List<String> nullableList(List<String> value) {
        return value == null ? null : safe(value);
    }

    private List<String> safe(List<?> value) {
        return value == null ? List.of() : value.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList();
    }
}
