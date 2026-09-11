package online.yudream.base.plugin.mcnews.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;

/**
 * 推送执行：群聊走宿主消息 SPI（official 协议可用 Markdown），Webhook 直接 POST JSON 载荷，
 * 用户私信走 sendDirectToBoundUser。所有失败都折算成结果对象，不抛出到轮询主流程。
 */
public final class NewsPushService {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long STAGE_TIMEOUT_SECONDS = 20;

    private final FrameworkServices framework;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public NewsPushService(FrameworkServices framework, HttpClient http, ObjectMapper mapper) {
        this.framework = framework;
        this.http = http;
        this.mapper = mapper;
    }

    /** 推送到一个群聊/Webhook 目标，永远返回结果（ok 或 error 文案）。 */
    public NewsPushLog.TargetResult sendToTarget(NewsTarget target, String content, Map<String, Object> payload) {
        if (target.messaging()) {
            return sendMessaging(target, content);
        }
        return sendWebhook(target, payload);
    }

    /** 推送给绑定私信订阅用户。 */
    public NewsPushLog.TargetResult sendDirect(String userId, String content) {
        try {
            var stage = framework.messaging().sendDirectToBoundUser(userId,
                    new PluginMessageContent(PluginMessageContent.Type.TEXT, content, null, null));
            stage.toCompletableFuture().get(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new NewsPushLog.TargetResult("direct:" + userId, "绑定私信", "direct", true, "");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NewsPushLog.TargetResult("direct:" + userId, "绑定私信", "direct", false, "发送被中断");
        }
        catch (Exception e) {
            return new NewsPushLog.TargetResult("direct:" + userId, "绑定私信", "direct", false, errorText(e));
        }
    }

    private NewsPushLog.TargetResult sendMessaging(NewsTarget target, String content) {
        try {
            PluginMessageContent.Type type = target.markdown()
                    ? PluginMessageContent.Type.MARKDOWN
                    : PluginMessageContent.Type.TEXT;
            var stage = framework.messaging().sendToChannel(target.connectionId(), target.channelId(),
                    new PluginMessageContent(type, content, null, null));
            stage.toCompletableFuture().get(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), true, "");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), false, "发送被中断");
        }
        catch (Exception e) {
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), false, errorText(e));
        }
    }

    private NewsPushLog.TargetResult sendWebhook(NewsTarget target, Map<String, Object> payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.webhookUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "YuDream-McNews");
            for (NewsTarget.WebhookHeader header : target.headers()) {
                headers.put(header.key(), header.value());
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), false,
                        "HTTP " + response.statusCode());
            }
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), true, "");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), false, "发送被中断");
        }
        catch (Exception e) {
            return new NewsPushLog.TargetResult(target.id(), displayName(target), target.type(), false, errorText(e));
        }
    }

    /** Webhook JSON 载荷：结构化字段 + 渲染后的推送文本，方便接收端按需取用。 */
    public Map<String, Object> webhookPayload(String event, NewsArticle article, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("plugin", "mc-news");
        payload.put("event", event);
        payload.put("title", article.title());
        payload.put("content", content);
        payload.put("summary", article.summary());
        payload.put("aiSummary", article.aiSummary());
        payload.put("body", article.body());
        payload.put("source", article.sourceId());
        payload.put("sourceName", article.sourceName());
        payload.put("category", article.category());
        payload.put("url", article.url());
        payload.put("imageUrl", article.imageUrl());
        payload.put("publishedAt", article.publishedAt() <= 0 ? "" : java.time.Instant.ofEpochMilli(article.publishedAt()).toString());
        payload.put("discoveredAt", java.time.Instant.ofEpochMilli(article.discoveredAt()).toString());
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    private String displayName(NewsTarget target) {
        return Objects.requireNonNullElse(target.name(), target.id());
    }

    private String errorText(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
