package online.yudream.plugin.webcard.domain;

import java.util.List;
import java.util.Map;

public final class WebCardModels {
    private WebCardModels() { }

    public enum AccessMode { PUBLIC_HTTP, CUSTOM_HEADERS }
    public enum SourceType { HTML, JSON, RSS, SITEMAP }
    public enum TemplateMode { STRUCTURED, ADVANCED }
    public enum DeliveryStage { DISCOVERED, FETCHED, PARSED, RENDERED, DELIVERED, DELAYED, FAILED }
    public enum ProposalStatus { PENDING, APPLIED, REJECTED }

    public record Site(String id, String name, boolean enabled, List<String> hosts,
                       AccessMode accessMode, List<String> headerNames, String secretRef,
                       SourceType responseType, List<String> redirectHosts,
                       String defaultTemplateId, long createdAt, long updatedAt) {
        public Site {
            hosts = hosts == null ? List.of() : hosts.stream().map(WebCardModels::host).distinct().toList();
            redirectHosts = redirectHosts == null ? List.of() : redirectHosts.stream().map(WebCardModels::host).distinct().toList();
            headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
            accessMode = accessMode == null ? AccessMode.PUBLIC_HTTP : accessMode;
            responseType = responseType == null ? SourceType.HTML : responseType;
            if (id == null || id.isBlank() || name == null || name.isBlank() || hosts.isEmpty()) throw new IllegalArgumentException("站点 ID、名称和域名不能为空");
        }
        public boolean matches(String candidate) { return hosts.contains(host(candidate)); }
        public boolean allowsRedirect(String candidate) { return matches(candidate) || redirectHosts.contains(host(candidate)); }
    }

    public record FieldRule(String name, String expression, String attribute, String type, boolean required) { }
    public record ParseRules(String siteId, SourceType detailType, List<FieldRule> fields,
                             String listExpression, String listLinkAttribute, String jsonItemsPath,
                             String canonicalField, String contentKeyField, String detailUrlPattern) {
        public ParseRules { detailType = detailType == null ? SourceType.HTML : detailType; fields = fields == null ? List.of() : List.copyOf(fields); detailUrlPattern = detailUrlPattern == null ? "" : detailUrlPattern.trim(); }
        public ParseRules(String siteId, SourceType detailType, List<FieldRule> fields, String listExpression,
                          String listLinkAttribute, String jsonItemsPath, String canonicalField, String contentKeyField) {
            this(siteId, detailType, fields, listExpression, listLinkAttribute, jsonItemsPath, canonicalField, contentKeyField, "");
        }
    }
    public record Template(String id, String siteId, String name, TemplateMode mode,
                           String draftVersionId, String publishedVersionId, long createdAt, long updatedAt) { }
    public record TemplateVersion(String id, String templateId, int version, ParseRules parseRules,
                                  TemplateMode mode, String structuredLayout, String html, String css,
                                  Map<String, Object> fixture, String origin, String summary,
                                  boolean previewPassed, long createdAt) {
        public TemplateVersion { fixture = fixture == null ? Map.of() : Map.copyOf(fixture); mode = mode == null ? TemplateMode.STRUCTURED : mode; }
    }
    public record GroupBinding(String id, String siteId, String connectionId, String platform,
                               String selfId, String channelId, boolean enabled, String templateVersionId,
                               String quietStart, String quietEnd, int cooldownSeconds, int hourlyLimit,
                               long lastDeliveryAt, long createdAt, long updatedAt) { }
    public record CrawlJob(String id, String siteId, String sourceUrl, SourceType sourceType,
                           boolean enabled, int intervalMinutes, int initialItemCount,
                           long nextRunAt, String leaseOwner, long leaseUntil,
                           boolean initialized, long createdAt, long updatedAt) {
        public CrawlJob { sourceType = sourceType == null ? SourceType.RSS : sourceType; intervalMinutes = Math.max(1, intervalMinutes); initialItemCount = initialItemCount <= 0 ? 3 : Math.min(initialItemCount, 50); }
    }
    public record ContentRecord(String id, String siteId, String canonicalUrl, String contentKey,
                                Map<String, Object> fields, String templateVersionId, long discoveredAt, long updatedAt) {
        public ContentRecord { fields = fields == null ? Map.of() : Map.copyOf(fields); }
    }
    public record DeliveryRecord(String id, String contentId, String bindingId, String templateVersionId,
                                 DeliveryStage stage, String renderedBase64, int attempts,
                                 String error, long nextAttemptAt, long createdAt, long updatedAt) { }
    public record AgentSession(String id, String siteId, String templateId, String agentCode,
                               List<Map<String, String>> messages, long createdAt, long updatedAt) {
        public AgentSession { messages = messages == null ? List.of() : List.copyOf(messages); }
    }
    public record PatchOperation(String target, String operation, Object value) { }
    public record AgentProposal(String id, String sessionId, String summary, List<PatchOperation> operations,
                                ProposalStatus status, String previewVersionId, long createdAt, long updatedAt) {
        public AgentProposal { operations = operations == null ? List.of() : List.copyOf(operations); status = status == null ? ProposalStatus.PENDING : status; }
    }
    public record Page<T>(List<T> records, long total) { }

    private static String host(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
