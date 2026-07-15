package online.yudream.plugin.qqbotautomation.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaJobService {
    private static final Pattern MEDIA_LINK = Pattern.compile("https?://(?:v\\.douyin\\.com|www\\.douyin\\.com|www\\.bilibili\\.com|b23\\.tv)/\\S+", Pattern.CASE_INSENSITIVE);
    private final AutomationPolicyService policies;
    private final PluginDocumentStore documents;
    private final FrameworkServices framework;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public MediaJobService(AutomationPolicyService policies, PluginDocumentStore documents, FrameworkServices framework) {
        this.policies = policies; this.documents = documents; this.framework = framework;
    }

    public void handle(PluginEvent event) {
        AutomationPolicy policy = policies.get(event.connectionId(), event.channelId());
        if (!policy.enabled() || !policy.mediaEnabled()) return;
        Matcher matcher = MEDIA_LINK.matcher(event.content() == null ? "" : event.content());
        if (!matcher.find()) return;
        String url = matcher.group(); String id = UUID.randomUUID().toString();
        save(id, event, url, "QUEUED", null, null);
        URI endpoint = URI.create(policy.mediaProviderEndpoint().trim() + (policy.mediaProviderEndpoint().contains("?") ? "&" : "?") + "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
        client.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body).thenApply(this::downloadUrl).whenComplete((downloadUrl, error) -> {
                    if (error != null || downloadUrl == null || downloadUrl.isBlank()) { save(id, event, url, "FAILED", null, sanitize(error)); return; }
                    save(id, event, url, "COMPLETED", downloadUrl, null);
                    Map<String, Object> referrer = event.messageId() == null || event.messageId().isBlank() ? Map.of() : Map.of("message_id", event.messageId());
                    framework.messaging().send(new PluginMessageRequest(event.connectionId(), event.platform(), event.selfId(), event.channelId(),
                            new PluginMessageContent(PluginMessageContent.Type.FILE, downloadUrl, null, referrer)));
                });
    }

    public java.util.List<Map<String, Object>> page(int page, int size) { return documents.findAll("media-job", Math.max(page, 1), Math.max(Math.min(size, 100), 1)); }
    public long total() { return documents.count("media-job"); }

    private String downloadUrl(String value) {
        try { return findUrl(json.readTree(value)); } catch (Exception ignored) { return null; }
    }
    private String findUrl(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String key : java.util.List.of("downloadUrl", "download_url", "url", "playUrl", "play_url")) if (node.hasNonNull(key) && node.get(key).isTextual()) return node.get(key).asText();
            java.util.Iterator<JsonNode> values = node.elements(); while (values.hasNext()) { String result = findUrl(values.next()); if (result != null) return result; }
        }
        if (node.isArray()) for (JsonNode item : node) { String result = findUrl(item); if (result != null) return result; }
        return null;
    }
    private void save(String id, PluginEvent event, String sourceUrl, String status, String downloadUrl, String error) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("id", id); value.put("connectionId", event.connectionId()); value.put("channelId", event.channelId()); value.put("sourceUrl", sourceUrl); value.put("status", status); value.put("createdAt", System.currentTimeMillis());
        if (downloadUrl != null) value.put("downloadUrl", downloadUrl); if (error != null) value.put("error", error); documents.save("media-job", id, value);
    }
    private String sanitize(Throwable error) {
        if (error == null || error.getMessage() == null) return "媒体解析失败";
        String message = error.getMessage().replaceAll("https?://[^\\s]+", "[endpoint]");
        return message.substring(0, Math.min(message.length(), 240));
    }
}
