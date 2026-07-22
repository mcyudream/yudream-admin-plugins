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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int DOCUMENT_SCAN_SIZE = 200;
    private static final long FALLBACK_FORWARD_UIN = 10001L;
    private static final Duration MEDIA_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern MEDIA_LINK = Pattern.compile("https?://(?:v\\.douyin\\.com|www\\.douyin\\.com|www\\.bilibili\\.com|b23\\.tv)/\\S+", Pattern.CASE_INSENSITIVE);
    private final AutomationPolicyService policies;
    private final PluginDocumentStore documents;
    private final FrameworkServices framework;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(MEDIA_TIMEOUT).build();
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
                new DeliveryTarget(event.connectionId(), event.platform(), event.selfId(), event.channelId(), replyTo(event), event.selfId()), "EVENT");
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
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        List<Map<String, Object>> jobs = allJobs();
        jobs.sort(Comparator.comparingLong(this::createdAt).reversed());
        int from = Math.min((safePage - 1) * safeSize, jobs.size());
        int to = Math.min(from + safeSize, jobs.size());
        return List.copyOf(jobs.subList(from, to));
    }

    public long total() {
        return documents.count("media-job");
    }

    public Map<String, Object> find(String id) {
        if (id == null || id.isBlank()) return null;
        return documents.findById("media-job", id).orElse(null);
    }

    public long clear() {
        long deleted = 0;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll("media-job", 1, DOCUMENT_SCAN_SIZE);
            if (batch.isEmpty()) return deleted;
            for (Map<String, Object> job : batch) {
                Object id = job.get("id");
                if (id != null) {
                    documents.delete("media-job", String.valueOf(id));
                    deleted++;
                }
            }
        }
    }

    private List<Map<String, Object>> allJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int currentPage = 1; ; currentPage++) {
            List<Map<String, Object>> batch = documents.findAll("media-job", currentPage, DOCUMENT_SCAN_SIZE);
            jobs.addAll(batch);
            if (batch.size() < DOCUMENT_SCAN_SIZE) return jobs;
        }
    }

    private long createdAt(Map<String, Object> job) {
        Object value = job.get("createdAt");
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private void start(String id, String connectionId, String channelId, String sourceUrl, AutomationPolicy policy,
                       DeliveryTarget target, String trigger) {
        save(id, connectionId, channelId, sourceUrl, trigger, "QUEUED", null, null);
        MediaRequest request = request(policy, sourceUrl);
        resolveMediaWithRetry(request, 3).whenComplete((media, error) -> {
                    if (error != null || media == null || media.deliveryUri().isBlank()) {
                        if (isDouyinImageDownload(request, error)) {
                            sendDouyinImagePost(request, target).whenComplete((ignored, imageError) -> {
                                if (imageError != null) {
                                    save(id, connectionId, channelId, sourceUrl, trigger, "FAILED", null, sanitize(imageError));
                                    return;
                                }
                                save(id, connectionId, channelId, sourceUrl, trigger, "COMPLETED", request.endpoint().toString(), null);
                            });
                            return;
                        }
                        save(id, connectionId, channelId, sourceUrl, trigger, "FAILED", null, sanitize(error));
                        return;
                    }
                    deliver(request, target, media).whenComplete((delivery, sendError) -> {
                        if (sendError != null) {
                            save(id, connectionId, channelId, sourceUrl, trigger, "SEND_FAILED", media.downloadUrl(), sanitize(sendError));
                            return;
                        }
                        if (delivery.commentError() != null) saveCommentError(id, sanitize(delivery.commentError()));
                        save(id, connectionId, channelId, sourceUrl, trigger, "COMPLETED", media.downloadUrl(), null);
                    });
                });
    }

    private CompletionStage<DeliveryResult> deliver(MediaRequest request, DeliveryTarget target, ResolvedMedia media) {
        if (!request.dockerDownload() || !isDouyinSource(request.sourceUrl())) {
            return sendResult(target, media.deliveryUri()).thenApply(ignored -> DeliveryResult.success());
        }
        return fetchDouyinCommentsForMedia(request, douyinAwemeId(media.deliveryUri()), target)
                .handle((comments, error) -> new CommentFetch(comments == null ? List.of() : comments, error))
                .thenCompose(result -> sendForward(target, videoForwardMessages(target, media.deliveryUri(), result.messages()),
                        "Douyin media and comments", "Media and comments", "Douyin media and comments")
                        // QQ clients cannot play records embedded in a forward message. Send it afterwards instead.
                        .thenCompose(ignored -> sendDouyinAudio(request, target))
                        .thenApply(ignored -> new DeliveryResult(result.error())));
    }

    private java.util.concurrent.CompletionStage<?> sendResult(DeliveryTarget target, String downloadUrl) {
        return framework.messaging().send(new PluginMessageRequest(target.connectionId(), target.platform(), target.selfId(), target.channelId(),
                new PluginMessageContent(PluginMessageContent.Type.VIDEO, downloadUrl,
                        java.util.List.of(new PluginMessageContent.Attachment(downloadUrl, "video.mp4", "video/mp4")), target.referrer())));
    }

    private CompletionStage<Map<String, Object>> sendForward(DeliveryTarget target, List<Map<String, Object>> messages,
                                                              String title, String summary, String prompt) {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("messages", messages);
        forward.put("title", title);
        forward.put("preview", messages.stream().limit(4)
                .map(message -> String.valueOf(message.get("sender_name")))
                .toList());
        forward.put("summary", summary);
        forward.put("prompt", prompt);
        return framework.messagingRaw().invoke(target.connectionId(), "send_group_message", Map.of(
                "group_id", target.channelId(),
                "message", List.of(Map.of("type", "forward", "data", forward))
        ));
    }

    private CompletionStage<?> sendDouyinImagePost(MediaRequest request, DeliveryTarget target) {
        URI endpoint = douyinMetadataEndpoint(request, false);
        HttpRequest metadataRequest = HttpRequest.newBuilder(endpoint).timeout(MEDIA_TIMEOUT).GET().build();
        return client.sendAsync(metadataRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::douyinMetadata)
                .thenCompose(data -> {
                    List<String> images = douyinImageUrls(data);
                    if (images.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("Douyin image post did not return images"));
                    String nickname = nonBlank(data.path("author").path("nickname").asText())
                            ? data.path("author").path("nickname").asText() : "Douyin media";
                    long userId = forwardUserId(target.forwardFallbackUserId());
                    List<Map<String, Object>> messages = new ArrayList<>(images.stream()
                            .map(uri -> forwardNode(nickname, userId, Map.of("type", "image", "data", Map.of("uri", uri))))
                            .toList());
                    return localizeDouyinAudio(douyinAudioUrl(data)).handle((audioUri, ignored) -> audioUri)
                            .thenCompose(audioUri -> {
                                return fetchDouyinCommentsForMedia(request, data.path("aweme_id").asText(), target)
                                        .exceptionally(ignored -> List.of())
                                        .thenCompose(comments -> {
                                            messages.addAll(comments);
                                            return sendForward(target, messages, "Douyin media and comments",
                                                    "Media and comments", "Douyin media and comments")
                                                    .thenCompose(ignored -> sendDouyinRecord(target, audioUri));
                                        });
                            });
                });
    }

    private CompletionStage<String> localizeDouyinAudio(String audioUrl) {
        if (!nonBlank(audioUrl)) return CompletableFuture.completedFuture(null);
        String hostDirectory = runtimeSetting("YUDREAM_QQBOT_MILKY_MEDIA_HOST_DIRECTORY", null);
        String containerDirectory = runtimeSetting("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY", DEFAULT_MILKY_MEDIA_DIRECTORY);
        if (!nonBlank(hostDirectory) || !containerDirectory.startsWith("/")) return CompletableFuture.completedFuture(null);
        try {
            Path destination = Path.of(hostDirectory).resolve("douyin_audio");
            Files.createDirectories(destination);
            String filename = "douyin_audio_" + UUID.randomUUID() + ".mp3";
            Path temporary = Files.createTempFile(destination, ".audio-", ".part");
            HttpRequest request = HttpRequest.newBuilder(URI.create(audioUrl)).timeout(MEDIA_TIMEOUT).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(temporary)).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Douyin audio HTTP " + response.statusCode());
                }
                try {
                    Files.move(temporary, destination.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not store Douyin audio for Milky", exception);
                }
                return "file://" + containerDirectory.replaceAll("/+$", "") + "/douyin_audio/" + filename;
            });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<?> sendDouyinAudio(MediaRequest request, DeliveryTarget target) {
        URI endpoint = douyinMetadataEndpoint(request, true);
        HttpRequest metadataRequest = HttpRequest.newBuilder(endpoint).timeout(MEDIA_TIMEOUT).GET().build();
        return client.sendAsync(metadataRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::douyinMetadata)
                .thenApply(this::douyinAudioUrl)
                .thenCompose(this::localizeDouyinAudio)
                .thenCompose(audioUri -> sendDouyinRecord(target, audioUri))
                // Audio is an enhancement. A failed download or record send must not fail delivered media/comments.
                .exceptionally(ignored -> null);
    }

    private CompletionStage<?> sendDouyinRecord(DeliveryTarget target, String audioUrl) {
        if (!nonBlank(audioUrl)) return CompletableFuture.completedFuture(null);
        return framework.messaging().send(new PluginMessageRequest(target.connectionId(), target.platform(), target.selfId(), target.channelId(),
                new PluginMessageContent(PluginMessageContent.Type.AUDIO, audioUrl,
                        List.of(new PluginMessageContent.Attachment(audioUrl, "douyin-audio.mp3", "audio/mpeg")), Map.of())))
                .exceptionally(ignored -> null);
    }

    private JsonNode douyinMetadata(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Douyin metadata HTTP " + response.statusCode() + ": " + providerMessage(response.body()));
        }
        try {
            JsonNode data = json.readTree(response.body()).path("data");
            if (!data.isObject()) throw new IllegalStateException("Douyin metadata did not return data");
            return data;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Douyin metadata returned invalid JSON", exception);
        }
    }

    private List<String> douyinImageUrls(JsonNode data) {
        List<String> urls = new ArrayList<>();
        for (JsonNode url : data.path("image_data").path("no_watermark_image_list")) {
            if (url.isTextual() && nonBlank(url.asText())) urls.add(url.asText());
        }
        if (!urls.isEmpty()) return urls;
        for (JsonNode image : data.path("images")) {
            String url = firstUrl(image.path("url_list"));
            if (nonBlank(url)) urls.add(url);
        }
        return urls;
    }

    private String douyinAudioUrl(JsonNode data) {
        for (JsonNode value : List.of(
                data.path("video").path("play_addr").path("url_list"),
                data.path("music").path("play_url").path("url_list"),
                data.path("video_data").path("audio_url_list"))) {
            String url = firstUrl(value);
            if (nonBlank(url)) return url;
        }
        String uri = data.path("video").path("play_addr").path("uri").asText();
        return uri.startsWith("http://") || uri.startsWith("https://") ? uri : null;
    }

    private List<Map<String, Object>> textOnly(List<Map<String, Object>> messages) {
        return messages.stream()
                .filter(message -> message.get("segments") instanceof List<?> segments
                        && !segments.isEmpty()
                        && segments.getFirst() instanceof Map<?, ?> segment
                        && "text".equals(segment.get("type")))
                .toList();
    }

    private CompletionStage<List<Map<String, Object>>> fetchDouyinCommentsForMedia(MediaRequest request, String knownAwemeId, DeliveryTarget target) {
        if (nonBlank(knownAwemeId)) return fetchDouyinComments(request, knownAwemeId, target);
        URI metadataEndpoint = douyinMetadataEndpoint(request, true);
        HttpRequest metadataRequest = HttpRequest.newBuilder(metadataEndpoint).timeout(MEDIA_TIMEOUT).GET().build();
        return client.sendAsync(metadataRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::douyinAwemeId)
                .thenCompose(awemeId -> fetchDouyinComments(request, awemeId, target));
    }

    private CompletionStage<List<Map<String, Object>>> fetchDouyinComments(MediaRequest request, String awemeId, DeliveryTarget target) {
        URI commentsEndpoint = URI.create(douyinApiEndpoint(request.endpoint(), "/api/douyin/web/fetch_video_comments")
                + "?aweme_id=" + URLEncoder.encode(awemeId, StandardCharsets.UTF_8) + "&cursor=0&count=15");
        HttpRequest commentsRequest = HttpRequest.newBuilder(commentsEndpoint).timeout(MEDIA_TIMEOUT).GET().build();
        return client.sendAsync(commentsRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> forwardCommentMessages(response, target.forwardFallbackUserId()));
    }

    private String douyinAwemeId(String deliveryUri) {
        Matcher matcher = Pattern.compile("douyin_(\\d+)(?:\\D|$)").matcher(deliveryUri == null ? "" : deliveryUri);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<Map<String, Object>> videoForwardMessages(DeliveryTarget target, String mediaUri,
                                                             List<Map<String, Object>> comments) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(forwardNode("Douyin media", forwardUserId(target.forwardFallbackUserId()),
                Map.of("type", "video", "data", Map.of("uri", mediaUri))));
        messages.addAll(comments);
        return messages;
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
                long userId = forwardUserId(fallbackUserId);
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

    private long forwardUserId(String fallbackUserId) {
        try {
            long userId = Long.parseLong(fallbackUserId);
            return userId >= FALLBACK_FORWARD_UIN && userId <= 4_294_967_295L ? userId : FALLBACK_FORWARD_UIN;
        } catch (RuntimeException ignored) {
            return FALLBACK_FORWARD_UIN;
        }
    }

    private String firstUrl(JsonNode urls) {
        return urls.isArray() && !urls.isEmpty() && urls.get(0).isTextual() ? urls.get(0).asText() : null;
    }

    private boolean isDouyinSource(String sourceUrl) {
        return sourceUrl != null && (sourceUrl.contains("v.douyin.com") || sourceUrl.contains("www.douyin.com"));
    }

    private boolean isBilibiliSource(String sourceUrl) {
        return sourceUrl != null && (sourceUrl.contains("b23.tv") || sourceUrl.contains("bilibili.com"));
    }

    private URI douyinApiEndpoint(URI requestEndpoint, String path) {
        return URI.create(requestEndpoint.getScheme() + "://" + requestEndpoint.getAuthority() + path);
    }

    private URI douyinMetadataEndpoint(MediaRequest request, boolean minimal) {
        return URI.create(appendUrlQuery(douyinApiEndpoint(request.endpoint(), "/api/hybrid/video_data"), request.sourceUrl())
                + "&minimal=" + minimal);
    }

    private boolean isDouyinImageDownload(MediaRequest request, Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) root = root.getCause();
        return request.dockerDownload() && isDouyinSource(request.sourceUrl()) && root instanceof DouyinImageDownloadException;
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String runtimeSetting(String name, String fallback) {
        String environmentValue = System.getenv(name);
        if (nonBlank(environmentValue)) return environmentValue;
        return System.getProperty(name, fallback);
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
        if (isBilibiliSource(sourceUrl) && isDouyinDockerEndpoint(configuredEndpoint)) {
            return new MediaRequest(appendBilibiliDownloadQuery(configuredEndpoint, sourceUrl), true, sourceUrl);
        }
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
        HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint()).timeout(MEDIA_TIMEOUT).GET().build();
        return client.sendAsync(httpRequest, bodyHandler(request)).thenApply(response -> {
            String downloadUrl = downloadUrl(request, response);
            String deliveryUri = request.dockerDownload() ? sharedFileUri(response) : downloadUrl;
            return new ResolvedMedia(downloadUrl, deliveryUri);
        });
    }

    private CompletionStage<ResolvedMedia> resolveMediaWithRetry(MediaRequest request, int attempts) {
        return resolveMedia(request).handle((media, error) -> {
            if (error == null || attempts <= 1 || !retryableParserFailure(error)) {
                return error == null ? CompletableFuture.completedFuture(media) : CompletableFuture.<ResolvedMedia>failedFuture(error);
            }
            return CompletableFuture.supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS))
                    .thenCompose(ignored -> resolveMediaWithRetry(request, attempts - 1));
        }).thenCompose(stage -> stage);
    }

    private boolean retryableParserFailure(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) root = root.getCause();
        String message = root == null ? "" : String.valueOf(root.getMessage());
        return message.contains("AwemeIdFetcher") || message.contains("请求端点失败")
                || message.contains("Media provider HTTP 502") || message.contains("Media provider HTTP 503");
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
        if (filename.endsWith("_images.zip") || filename.endsWith("_images_watermark.zip")) {
            throw new DouyinImageDownloadException();
        }
        String directory = filename.contains("_douyin_") || filename.startsWith("douyin_") ? "douyin_video"
                : filename.contains("_tiktok_") || filename.startsWith("tiktok_") ? "tiktok_video"
                : filename.startsWith("bilibili_") ? "bilibili_video" : null;
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

    private URI appendBilibiliDownloadQuery(URI configured, String sourceUrl) {
        String configuredText = configured.toString();
        int queryIndex = configuredText.indexOf('?');
        String base = queryIndex >= 0 ? configuredText.substring(0, queryIndex) : configuredText;
        String query = queryIndex >= 0 ? configuredText.substring(queryIndex) : "";
        String rawPath = configured.getRawPath();
        String origin = rawPath == null || rawPath.isEmpty() ? base : base.substring(0, base.length() - rawPath.length());
        return URI.create(origin + "/api/bilibili/web/download" + query
                + (query.isEmpty() ? "?" : "&") + "url=" + URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8));
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
        if (root == null) return "Media parsing failed";
        String detail = root.getMessage();
        if (detail == null || detail.isBlank()) detail = root.getClass().getSimpleName();
        String message = detail.replaceAll("https?://[^\\s]+", "[endpoint]");
        return message.substring(0, Math.min(message.length(), 240));
    }

    private record MediaRequest(URI endpoint, boolean dockerDownload, String sourceUrl) { }
    private record ResolvedMedia(String downloadUrl, String deliveryUri) { }
    private record CommentFetch(List<Map<String, Object>> messages, Throwable error) { }
    private record DeliveryResult(Throwable commentError) {
        private static DeliveryResult success() { return new DeliveryResult(null); }
    }
    private record DeliveryTarget(String connectionId, String platform, String selfId, String channelId,
                                  Map<String, Object> referrer, String forwardFallbackUserId) { }
    private static final class DouyinImageDownloadException extends IllegalStateException { }
}
