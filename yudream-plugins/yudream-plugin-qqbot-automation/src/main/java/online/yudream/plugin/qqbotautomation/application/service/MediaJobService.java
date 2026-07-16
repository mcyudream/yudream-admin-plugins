package online.yudream.plugin.qqbotautomation.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.MediaJobTestRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaJobService {
    private static final String DEFAULT_DOCKER_ENDPOINT = "http://127.0.0.1";
    private static final String DEFAULT_MILKY_MEDIA_DIRECTORY = "/media";
    private static final Pattern MEDIA_LINK = Pattern.compile("https?://(?:v\\.douyin\\.com|www\\.douyin\\.com|www\\.bilibili\\.com|b23\\.tv)/\\S+", Pattern.CASE_INSENSITIVE);
    private final AutomationPolicyService policies;
    private final PluginDocumentStore documents;
    private final FrameworkServices framework;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public MediaJobService(AutomationPolicyService policies, PluginDocumentStore documents, FrameworkServices framework) {
        this.policies = policies;
        this.documents = documents;
        this.framework = framework;
    }

    public void handle(PluginEvent event) {
        AutomationPolicy policy = policies.resolve(event.connectionId(), event.channelId());
        if (!policy.enabled() || !policy.mediaEnabled()) return;
        Matcher matcher = MEDIA_LINK.matcher(event.content() == null ? "" : event.content());
        if (!matcher.find()) return;
        start(UUID.randomUUID().toString(), event.connectionId(), event.channelId(), matcher.group(), policy,
                new DeliveryTarget(event.connectionId(), event.platform(), event.selfId(), event.channelId(), replyTo(event), event.userId()), "EVENT");
    }

    /**
     * Starts an administrator-requested parser job and sends the result to the selected QQ group.
     */
    public String startTest(MediaJobTestRequest request) {
        if (request == null) throw new IllegalArgumentException("Media test request cannot be null");
        String connectionId = required(request.connectionId(), "connectionId");
        String channelId = required(request.channelId(), "channelId");
        Matcher matcher = MEDIA_LINK.matcher(required(request.sourceUrl(), "sourceUrl"));
        if (!matcher.find()) {
            throw new IllegalArgumentException("Only supported Douyin and Bilibili links can be tested");
        }
        String sourceUrl = matcher.group();
        AutomationPolicy policy = policies.resolve(connectionId, channelId);
        String id = UUID.randomUUID().toString();
        start(id, connectionId, channelId, sourceUrl, policy,
                deliveryTarget(connectionId, channelId), "MANUAL_TEST");
        return id;
    }

    public java.util.List<Map<String, Object>> page(int page, int size) {
        return documents.findAll("media-job", Math.max(page, 1), Math.max(Math.min(size, 100), 1));
    }

    public long total() {
        return documents.count("media-job");
    }

    public Map<String, Object> find(String id) {
        if (id == null || id.isBlank()) return null;
        return documents.findById("media-job", id).orElse(null);
    }

    private void start(String id, String connectionId, String channelId, String sourceUrl, AutomationPolicy policy,
                       DeliveryTarget target, String trigger) {
        save(id, connectionId, channelId, sourceUrl, trigger, "QUEUED", null, null);
        MediaRequest request = request(policy, sourceUrl);
        resolveMedia(request).whenComplete((media, error) -> {
                    if (error != null || media == null || media.deliveryUri().isBlank()) {
                        save(id, connectionId, channelId, sourceUrl, trigger, "FAILED", null, sanitize(error));
                        return;
                    }
                    sendResult(target, media.deliveryUri()).whenComplete((ignored, sendError) -> {
                        if (sendError != null) {
                            save(id, connectionId, channelId, sourceUrl, trigger, "SEND_FAILED", media.downloadUrl(), sanitize(sendError));
                            return;
                        }
                        sendDouyinComments(request, target).whenComplete((commentResult, commentError) -> {
                            if (commentError != null) {
                                saveCommentError(id, sanitize(commentError));
                            }
                            save(id, connectionId, channelId, sourceUrl, trigger, "COMPLETED", media.downloadUrl(), null);
                        });
                    });
                });
    }

    private java.util.concurrent.CompletionStage<?> sendResult(DeliveryTarget target, String downloadUrl) {
        return framework.messaging().send(new PluginMessageRequest(target.connectionId(), target.platform(), target.selfId(), target.channelId(),
                new PluginMessageContent(PluginMessageContent.Type.VIDEO, downloadUrl,
                        java.util.List.of(new PluginMessageContent.Attachment(downloadUrl, "video.mp4", "video/mp4")), target.referrer())));
    }

    /**
     * Mirrors the legacy KleinBlue flow: media first, then the first page of Douyin comments as a forward message.
     * Comment delivery is deliberately best-effort so an unavailable comment API never changes a sent video into a failure.
     */
    private CompletionStage<?> sendDouyinComments(MediaRequest request, DeliveryTarget target) {
        if (!request.dockerDownload() || !isDouyinSource(request.sourceUrl())) {
            return CompletableFuture.completedFuture(null);
        }
        return fetchDouyinComments(request, target).thenCompose(messages -> {
            if (messages.isEmpty()) return CompletableFuture.completedFuture(null);
            try {
                Map<String, Object> forward = new LinkedHashMap<>();
                forward.put("messages", messages);
                forward.put("title", "抖音评论");
                forward.put("preview", messages.stream().limit(4)
                        .map(message -> String.valueOf(message.get("sender_name")))
                        .toList());
                forward.put("summary", "评论区");
                forward.put("prompt", "抖音评论");
                String content = json.writeValueAsString(forward);
                return framework.messaging().send(new PluginMessageRequest(target.connectionId(), target.platform(), target.selfId(), target.channelId(),
                        new PluginMessageContent(PluginMessageContent.Type.COMPOSITE, content, List.of(), Map.of())));
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(new IllegalStateException("Unable to build Douyin comments message", exception));
            }
        });
    }

    private CompletionStage<List<Map<String, Object>>> fetchDouyinComments(MediaRequest request, DeliveryTarget target) {
        URI metadataEndpoint = URI.create(appendUrlQuery(douyinApiEndpoint(request.endpoint(), "/api/hybrid/video_data"), request.sourceUrl()) + "&minimal=true");
        HttpRequest metadataRequest = HttpRequest.newBuilder(metadataEndpoint).timeout(Duration.ofSeconds(30)).GET().build();
        return client.sendAsync(metadataRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::douyinAwemeId)
                .thenCompose(awemeId -> {
                    URI commentsEndpoint = URI.create(douyinApiEndpoint(request.endpoint(), "/api/douyin/web/fetch_video_comments")
                            + "?aweme_id=" + URLEncoder.encode(awemeId, StandardCharsets.UTF_8) + "&cursor=0&count=15");
                    HttpRequest commentsRequest = HttpRequest.newBuilder(commentsEndpoint).timeout(Duration.ofSeconds(30)).GET().build();
                    return client.sendAsync(commentsRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                })
                .thenApply(response -> forwardCommentMessages(response, target.forwardFallbackUserId()));
    }

    private String douyinAwemeId(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Douyin metadata HTTP " + response.statusCode() + ": " + providerMessage(response.body()));
        }
        try {
            JsonNode awemeId = json.readTree(response.body()).path("data").path("aweme_id");
            if (!awemeId.isTextual() && !awemeId.isNumber()) {
                throw new IllegalStateException("Douyin metadata did not return aweme_id");
            }
            return awemeId.asText();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Douyin metadata returned invalid JSON", exception);
        }
    }

    private List<Map<String, Object>> forwardCommentMessages(HttpResponse<String> response, String fallbackUserId) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Douyin comments HTTP " + response.statusCode() + ": " + providerMessage(response.body()));
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            JsonNode comments = json.readTree(response.body()).path("data").path("comments");
            if (!comments.isArray()) return messages;
            for (JsonNode comment : comments) {
                JsonNode user = comment.path("user");
                String nickname = nonBlank(user.path("nickname").asText()) ? user.path("nickname").asText() : "抖音用户";
                long userId = forwardUserId(user, fallbackUserId);
                addImageNode(messages, nickname, userId, firstUrl(comment.path("sticker").path("static_url").path("url_list")));
                for (JsonNode image : comment.path("image_list")) {
                    addImageNode(messages, nickname, userId, firstUrl(image.path("origin_url").path("url_list")));
                }
                String text = comment.path("text").asText();
                if (nonBlank(text)) messages.add(forwardNode(nickname, userId, Map.of("type", "text", "data", Map.of("text", text))));
            }
            return messages;
        } catch (Exception exception) {
            throw new IllegalStateException("Douyin comments returned invalid JSON", exception);
        }
    }

    private void addImageNode(List<Map<String, Object>> messages, String nickname, long userId, String uri) {
        if (nonBlank(uri)) messages.add(forwardNode(nickname, userId, Map.of("type", "image", "data", Map.of("uri", uri))));
    }

    private Map<String, Object> forwardNode(String nickname, long userId, Map<String, Object> segment) {
        return Map.of("user_id", userId, "sender_name", nickname, "time", System.currentTimeMillis() / 1000,
                "segments", List.of(segment));
    }

    private long forwardUserId(JsonNode user, String fallbackUserId) {
        for (String field : List.of("id", "uid", "user_id")) {
            JsonNode value = user.get(field);
            if (value != null && value.canConvertToLong()) return value.asLong();
            if (value != null && value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next possible provider field.
                }
            }
        }
        try {
            return Long.parseLong(fallbackUserId);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private String firstUrl(JsonNode urls) {
        return urls.isArray() && !urls.isEmpty() && urls.get(0).isTextual() ? urls.get(0).asText() : null;
    }

    private boolean isDouyinSource(String sourceUrl) {
        return sourceUrl != null && (sourceUrl.contains("v.douyin.com") || sourceUrl.contains("www.douyin.com"));
    }

    private URI douyinApiEndpoint(URI requestEndpoint, String path) {
        return URI.create(requestEndpoint.getScheme() + "://" + requestEndpoint.getAuthority() + path);
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Object> replyTo(PluginEvent event) {
        return event.messageId() == null || event.messageId().isBlank() ? Map.of() : Map.of("message_id", event.messageId());
    }

    private DeliveryTarget deliveryTarget(String connectionId, String channelId) {
        return framework.messaging().connections().stream()
                .filter(connection -> connectionId.equals(connection.id()))
                .findFirst()
                .map(connection -> new DeliveryTarget(connection.id(), connection.platform(), connection.userId(), channelId, Map.of(), connection.userId()))
                .orElseThrow(() -> new IllegalArgumentException("Messaging connection is unavailable"));
    }

    private MediaRequest request(AutomationPolicy policy, String sourceUrl) {
        String configured = configuredEndpoint(policy);
        URI configuredEndpoint = URI.create(configured);
        if (isDouyinDockerEndpoint(configuredEndpoint)) {
            URI downloadEndpoint = appendDouyinDownloadQuery(douyinDownloadEndpoint(configuredEndpoint), sourceUrl);
            return new MediaRequest(downloadEndpoint, true, sourceUrl);
        }
        URI endpoint = appendUrlQuery(configuredEndpoint, sourceUrl);
        return new MediaRequest(endpoint, false, sourceUrl);
    }

    private String configuredEndpoint(AutomationPolicy policy) {
        String configured = policy.mediaProviderEndpoint();
        return configured == null || configured.isBlank() ? DEFAULT_DOCKER_ENDPOINT : configured.trim();
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.trim();
    }

    private java.util.concurrent.CompletionStage<ResolvedMedia> resolveMedia(MediaRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint()).timeout(Duration.ofSeconds(90)).GET().build();
        return client.sendAsync(httpRequest, bodyHandler(request)).thenApply(response -> {
            String downloadUrl = downloadUrl(request, response);
            String deliveryUri = request.dockerDownload() ? sharedFileUri(response) : downloadUrl;
            return new ResolvedMedia(downloadUrl, deliveryUri);
        });
    }

    private String downloadUrl(MediaRequest request, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Media provider HTTP " + response.statusCode() + ": " + providerMessage(response.body()));
        }
        if (request.dockerDownload()) {
            if (!response.body().isBlank()) {
                throw new IllegalStateException("Media provider did not return a media file: " + providerMessage(response.body()));
            }
            return request.endpoint().toString();
        }
        try {
            String url = findUrl(json.readTree(response.body()));
            if (url != null && !url.isBlank()) return url;
            throw new IllegalStateException("Media provider did not return a download URL");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Media provider returned invalid JSON", exception);
        }
    }

    private String sharedFileUri(HttpResponse<?> response) {
        String filename = response.headers().firstValue("Content-Disposition")
                .flatMap(this::filename)
                .orElseThrow(() -> new IllegalStateException("Media provider did not include a downloadable filename"));
        String directory = filename.contains("_douyin_") || filename.startsWith("douyin_") ? "douyin_video"
                : filename.contains("_tiktok_") || filename.startsWith("tiktok_") ? "tiktok_video" : null;
        if (directory == null) {
            throw new IllegalStateException("Media provider returned an unsupported media filename: " + filename);
        }
        String root = System.getenv().getOrDefault("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY", DEFAULT_MILKY_MEDIA_DIRECTORY)
                .replace('\\', '/').replaceAll("/+$", "");
        if (!root.startsWith("/")) {
            throw new IllegalStateException("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY must be an absolute container path");
        }
        return URI.create("file://" + root + "/" + directory + "/" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")).toString();
    }

    private java.util.Optional<String> filename(String contentDisposition) {
        Matcher matcher = Pattern.compile("(?i)filename=\\\"?([^\\\";]+)").matcher(contentDisposition);
        return matcher.find() ? java.util.Optional.of(matcher.group(1).trim()) : java.util.Optional.empty();
    }

    private String findUrl(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String key : java.util.List.of(
                    "nwm_video_url_HQ", "nwm_video_url", "nwm_video_url_hq", "wm_video_url", "downloadUrl",
                    "download_url", "playUrl", "play_url", "url")) {
                String url = textualUrl(node.get(key));
                if (url != null) return url;
            }
            java.util.Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                String result = findUrl(values.next());
                if (result != null) return result;
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String result = findUrl(item);
                if (result != null) return result;
            }
        }
        return null;
    }

    private boolean isDouyinDockerEndpoint(URI endpoint) {
        String path = normalizePath(endpoint.getPath());
        return path.isEmpty() || "/api".equals(path) || "/api/hybrid/video_data".equals(path) || "/api/download".equals(path);
    }

    private URI douyinDownloadEndpoint(URI configured) {
        String path = normalizePath(configured.getPath());
        if ("/api/download".equals(path)) return configured;
        String configuredText = configured.toString();
        int queryIndex = configuredText.indexOf('?');
        String base = queryIndex >= 0 ? configuredText.substring(0, queryIndex) : configuredText;
        String query = queryIndex >= 0 ? configuredText.substring(queryIndex) : "";
        String rawPath = configured.getRawPath();
        String origin = rawPath == null || rawPath.isEmpty() ? base : base.substring(0, base.length() - rawPath.length());
        return URI.create(origin + "/api/download" + query);
    }

    private URI appendDouyinDownloadQuery(URI endpoint, String sourceUrl) {
        return URI.create(endpoint + (endpoint.getQuery() == null ? "?" : "&")
                + "url=" + URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8) + "&prefix=false&with_watermark=false");
    }

    private URI appendUrlQuery(URI endpoint, String sourceUrl) {
        return URI.create(endpoint + (endpoint.getQuery() == null ? "?" : "&")
                + "url=" + URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8));
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private String textualUrl(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            for (JsonNode value : node) {
                String url = textualUrl(value);
                if (url != null) return url;
            }
        }
        return null;
    }

    private HttpResponse.BodyHandler<String> bodyHandler(MediaRequest request) {
        if (!request.dockerDownload()) return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
        return response -> response.statusCode() >= 400 || isJson(response)
                ? HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
                : HttpResponse.BodySubscribers.replacing("");
    }

    private boolean isJson(HttpResponse.ResponseInfo response) {
        return response.headers().firstValue("Content-Type")
                .map(contentType -> contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json"))
                .orElse(false);
    }

    private String providerMessage(String body) {
        if (body == null || body.isBlank()) return "No error details returned";
        try {
            String message = findMessage(json.readTree(body));
            if (message != null && !message.isBlank()) return message;
        } catch (Exception ignored) {
            // Fall through to the bounded text response.
        }
        return body.replaceAll("\\s+", " ").substring(0, Math.min(body.length(), 180));
    }

    private String findMessage(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String key : java.util.List.of("message", "error", "detail")) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
                String nested = findMessage(value);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void save(String id, String connectionId, String channelId, String sourceUrl, String trigger,
                      String status, String downloadUrl, String error) {
        Map<String, Object> existing = documents.findById("media-job", id).orElse(Map.of());
        long createdAt = existing.get("createdAt") instanceof Number value ? value.longValue() : System.currentTimeMillis();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("connectionId", connectionId);
        value.put("channelId", channelId);
        value.put("sourceUrl", sourceUrl);
        value.put("trigger", trigger);
        value.put("status", status);
        value.put("createdAt", createdAt);
        if (!"QUEUED".equals(status)) value.put("completedAt", System.currentTimeMillis());
        if (downloadUrl != null) value.put("downloadUrl", downloadUrl);
        if (error != null) value.put("error", error);
        if (existing.containsKey("commentError")) value.put("commentError", existing.get("commentError"));
        documents.save("media-job", id, value);
    }

    private void saveCommentError(String id, String error) {
        documents.findById("media-job", id).ifPresent(existing -> {
            Map<String, Object> value = new LinkedHashMap<>(existing);
            value.put("commentError", error);
            documents.save("media-job", id, value);
        });
    }

    private String sanitize(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) root = root.getCause();
        if (root == null || root.getMessage() == null) return "Media parsing failed";
        String message = root.getMessage().replaceAll("https?://[^\\s]+", "[endpoint]");
        return message.substring(0, Math.min(message.length(), 240));
    }

    private record MediaRequest(URI endpoint, boolean dockerDownload, String sourceUrl) { }
    private record ResolvedMedia(String downloadUrl, String deliveryUri) { }
    private record DeliveryTarget(String connectionId, String platform, String selfId, String channelId,
                                  Map<String, Object> referrer, String forwardFallbackUserId) { }
}
