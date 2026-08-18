package online.yudream.plugin.aichatbot.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki 求助检索分支：经宿主公开 REST（/api/public/wiki/{slug}/search）检索知识库，
 * 并把命中页引用的站内图片（/api/files/{id}/content）以 QQ 图片消息发回群里，实现图文并茂的教程回复。
 */
public class AiChatbotWikiService {
    private static final Logger LOGGER = Logger.getLogger(AiChatbotWikiService.class.getName());
    private static final Pattern FILE_URL = Pattern.compile("/api/files/(\\d+)/content");
    private static final int MAX_HITS = 3;
    private static final int EXCERPT_LIMIT = 320;
    private static final int MAX_IMAGES_PER_HIT = 4;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** 求助/提问意图特征：疑问词、求助语气、故障描述与教程类诉求 */
    private static final Pattern HELP_INTENT = Pattern.compile(
            "怎么|怎样|如何|请问|求助|求救|为啥|为什么|怎么办|谁知道|有人知道|谁能|能不能告诉我|"
                    + "教程|攻略|指南|步骤|配置方法|使用方法|"
                    + "报错|错误|失败|异常|崩溃|打不开|进不去|连不上|无法|不能|不行|用不了|没反应|找不到|"
                    + "\\?|？|吗[。!！]?$|嘛[。!！]?$|呢[。!！]?$");

    private final FrameworkServices framework;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AiChatbotWikiService(FrameworkServices framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    public record WikiImage(String url, String caption) { }
    public record WikiHit(String title, String path, String spaceName, String excerpt, List<WikiImage> images) { }

    /** 判断被 @ 的消息是否带有求助/检索意图 */
    public boolean looksLikeHelp(String content) {
        if (content == null) {
            return false;
        }
        String value = content.trim();
        return value.length() >= 2 && HELP_INTENT.matcher(value).find();
    }

    /** 检索公开知识库，返回去洗后的命中（标题、路径、摘要、图片）；spaceSlug 为空时检索全部开放公开阅读的知识库 */
    public List<WikiHit> search(String spaceSlug, String query) {
        String base = "http://127.0.0.1:" + framework.setting("server.port").orElse("8080");
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("query", query));
        }
        catch (Exception error) {
            throw new IllegalStateException("检索请求构造失败", error);
        }
        String path = spaceSlug == null || spaceSlug.isBlank()
                ? "/api/public/wiki/search"
                : "/api/public/wiki/" + URLEncoder.encode(spaceSlug, StandardCharsets.UTF_8) + "/search";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        String responseBody;
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            responseBody = response.body();
        }
        catch (Exception error) {
            throw new IllegalStateException("Wiki 检索请求失败：" + error.getMessage(), error);
        }
        return parseHits(responseBody);
    }

    private List<WikiHit> parseHits(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        }
        catch (Exception error) {
            throw new IllegalStateException("Wiki 检索响应解析失败", error);
        }
        int code = root.path("code").asInt(-1);
        if (code != 200) {
            String message = root.path("message").asText("Wiki 检索失败");
            throw new IllegalStateException(message);
        }
        List<WikiHit> hits = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            if (!node.isObject()) {
                continue;
            }
            List<WikiImage> images = new ArrayList<>();
            for (JsonNode image : node.path("images")) {
                String url = image.path("url").asText("");
                if (!url.isBlank()) {
                    images.add(new WikiImage(url, image.path("caption").asText("")));
                }
                if (images.size() >= MAX_IMAGES_PER_HIT) {
                    break;
                }
            }
            hits.add(new WikiHit(
                    node.path("title").asText(""),
                    node.path("path").asText(""),
                    node.path("spaceName").asText(""),
                    truncate(node.path("content").asText(""), EXCERPT_LIMIT),
                    List.copyOf(images)));
            if (hits.size() >= MAX_HITS) {
                break;
            }
        }
        return List.copyOf(hits);
    }

    /** 汇总命中中的站内图片（按 URL 去重，保持命中顺序） */
    public List<WikiImage> collectImages(List<WikiHit> hits, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<WikiImage> images = new ArrayList<>();
        for (WikiHit hit : hits) {
            for (WikiImage image : hit.images()) {
                if (seen.add(image.url())) {
                    images.add(image);
                }
                if (images.size() >= Math.max(1, limit)) {
                    return List.copyOf(images);
                }
            }
        }
        return List.copyOf(images);
    }

    /**
     * 把站内图片读出来并以 QQ 图片消息发到群里；返回成功发送的张数。
     * 图片经 /api/files/{id}/content 地址解析出文件 ID，走平台文件存储读取，无需外网可达。
     */
    public int sendImages(String connectionId, String platform, String selfId, String channelId, String messageId,
                          List<WikiImage> images) {
        int sent = 0;
        for (WikiImage image : images) {
            String fileId = fileIdOf(image.url());
            if (fileId == null) {
                continue;
            }
            try {
                Optional<PluginStoredFile> stored = framework.platformFile(fileId);
                if (stored.isEmpty()) {
                    continue;
                }
                byte[] bytes = readBounded(stored.get().inputStream());
                if (bytes.length == 0) {
                    continue;
                }
                String uri = "base64://" + Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
                framework.messaging().send(new PluginMessageRequest(connectionId, platform, selfId, channelId,
                        new PluginMessageContent(PluginMessageContent.Type.IMAGE, uri, null, referrer)));
                sent++;
            }
            catch (Exception error) {
                LOGGER.log(Level.WARNING, "[YuDreamAdmin] [AI Chatbot] wiki image send failed: " + image.url() + " - " + error.getMessage(), error);
            }
        }
        return sent;
    }

    private String fileIdOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = FILE_URL.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private byte[] readBounded(InputStream input) throws Exception {
        try (input) {
            byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            return bytes.length > MAX_IMAGE_BYTES ? new byte[0] : bytes;
        }
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "…";
    }
}
