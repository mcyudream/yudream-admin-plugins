package online.yudream.plugin.qqbotautomation.application.service;

import com.sun.net.httpserver.HttpServer;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.MediaJobTestRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaJobServiceTest {

    @Test
    void manualTestCompletesAndSendsToTheSelectedGroup() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        AtomicReference<Map<String, Object>> forwardedPayload = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = responseServer("/api/download", requestUri, "media-content");
        try {
            server.start();
            int port = server.getAddress().getPort();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + port, false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, sentRequest, null, forwardedPayload));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "抖音分享文本 https://v.douyin.com/example 复制打开抖音"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            Map<String, Object> result = job(documents, jobId);
            assertEquals("MANUAL_TEST", result.get("trigger"));
            assertEquals("https://v.douyin.com/example", result.get("sourceUrl"));
            assertEquals("http://localhost:" + port + "/api/download?url=https%3A%2F%2Fv.douyin.com%2Fexample&prefix=false&with_watermark=false", result.get("downloadUrl"));
            assertEquals(result, service.find(jobId));
            assertEquals(1, sentMessages.get());
            assertEquals("group-a", forwardedPayload.get().get("group_id"));
            List<?> message = (List<?>) forwardedPayload.get().get("message");
            Map<?, ?> forward = (Map<?, ?>) ((Map<?, ?>) message.getFirst()).get("data");
            Map<?, ?> media = (Map<?, ?>) ((List<?>) forward.get("messages")).getFirst();
            assertEquals("video", ((Map<?, ?>) ((List<?>) media.get("segments")).getFirst()).get("type"));
            assertEquals("/api/download?url=https%3A%2F%2Fv.douyin.com%2Fexample&prefix=false&with_watermark=false", requestUri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsBilibiliShortLinksThroughTheDedicatedDockerEndpoint() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/bilibili/web/download", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"bilibili_BV1kFKG6pEvU_40009337567.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, sentRequest));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://b23.tv/ypOBBNS"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("/api/bilibili/web/download?url=https%3A%2F%2Fb23.tv%2FypOBBNS", requestUri.get());
            assertEquals("file:///media/bilibili_video/bilibili_BV1kFKG6pEvU_40009337567.mp4", sentRequest.get().content().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDouyinCommentsAsMergedForwardAfterVideo() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        List<PluginMessageRequest> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Map<String, Object>> forwardedPayload = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = douyinServer(200, """
                {"data":{"comments":[{"text":"第一条评论","user":{"id":"123456","nickname":"评论用户"},
                "sticker":{"static_url":{"url_list":["https://cdn.example.test/sticker.png"]}},
                "image_list":[{"origin_url":{"url_list":["https://cdn.example.test/image.png"]}}]}]}}
                """);
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, null, requests, forwardedPayload));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> sentMessages.get() == 1 && "COMPLETED".equals(job(documents, jobId).get("status")));
            Map<String, Object> forward = forwardedPayload.get();
            assertEquals("group-a", forward.get("group_id"));
            List<?> message = (List<?>) forward.get("message");
            Map<?, ?> segment = (Map<?, ?>) message.getFirst();
            assertEquals("forward", segment.get("type"));
            Map<?, ?> data = (Map<?, ?>) segment.get("data");
            assertEquals("Douyin media and comments", data.get("title"));
            List<?> nodes = (List<?>) data.get("messages");
            assertEquals(4, nodes.size());
            Map<?, ?> mediaNode = (Map<?, ?>) nodes.getFirst();
            assertEquals("video", ((Map<?, ?>) ((List<?>) mediaNode.get("segments")).getFirst()).get("type"));
            Map<?, ?> textNode = (Map<?, ?>) nodes.get(3);
            assertEquals("评论用户", textNode.get("sender_name"));
            assertEquals(10001L, textNode.get("user_id"));
            Map<?, ?> textSegment = (Map<?, ?>) ((List<?>) textNode.get("segments")).getFirst();
            assertEquals("第一条评论", ((Map<?, ?>) textSegment.get("data")).get("text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsCompletedVideoWhenDouyinCommentsFail() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = douyinServer(500, "{\"message\":\"comments unavailable\"}");
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals(1, sentMessages.get());
            assertTrue(String.valueOf(job(documents, jobId).get("commentError")).contains("Douyin comments HTTP 500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDouyinAudioAsASeparateRecordAfterTheMergedForward() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        List<PluginMessageRequest> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Map<String, Object>> forwardedPayload = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = douyinServer(200, "{\"data\":{\"comments\":[]}}");
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        String hostDirectoryPrevious = System.getProperty("YUDREAM_QQBOT_MILKY_MEDIA_HOST_DIRECTORY");
        String containerDirectoryPrevious = System.getProperty("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY");
        try {
            System.setProperty("YUDREAM_QQBOT_MILKY_MEDIA_HOST_DIRECTORY", mediaDirectory.toString());
            System.setProperty("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY", "/milky-media");
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, null, requests, forwardedPayload));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> sentMessages.get() == 2 && "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("forward", ((Map<?, ?>) ((List<?>) forwardedPayload.get().get("message")).getFirst()).get("type"));
            assertEquals(1, requests.size());
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.AUDIO, requests.getFirst().content().type());
            assertTrue(requests.getFirst().content().content().startsWith("file:///milky-media/douyin_audio/"));
        } finally {
            restoreProperty("YUDREAM_QQBOT_MILKY_MEDIA_HOST_DIRECTORY", hostDirectoryPrevious);
            restoreProperty("YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY", containerDirectoryPrevious);
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void pagesNewestJobsFirstAndClearsAllJobs() {
        InMemoryDocuments documents = new InMemoryDocuments();
        MediaJobService service = new MediaJobService(new AutomationPolicyService(documents), documents, framework(new AtomicInteger()));
        documents.save("media-job", "older", Map.of("id", "older", "createdAt", 100L));
        documents.save("media-job", "newest", Map.of("id", "newest", "createdAt", 300L));
        documents.save("media-job", "middle", Map.of("id", "middle", "createdAt", 200L));

        assertEquals(List.of("newest", "middle"), service.page(1, 2).stream().map(job -> String.valueOf(job.get("id"))).toList());
        assertEquals(3, service.clear());
        assertEquals(0, service.total());
        assertTrue(service.page(1, 10).isEmpty());
    }

    @Test
    void sendsDouyinImagePostAsOneForwardMessage() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        List<PluginMessageRequest> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Map<String, Object>> forwardedPayload = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = douyinImageServer();
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, null, requests, forwardedPayload));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/image-post"));

            await(() -> sentMessages.get() == 1 && "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("forward", ((Map<?, ?>) ((List<?>) forwardedPayload.get().get("message")).getFirst()).get("type"));
            Map<?, ?> forward = (Map<?, ?>) ((Map<?, ?>) ((List<?>) forwardedPayload.get().get("message")).getFirst()).get("data");
            assertEquals(2, ((List<?>) forward.get("messages")).size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsCustomProviderEndpointsCompatible() throws Exception {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = responseServer("/parse", requestUri,
                "{\"data\":{\"downloadUrl\":\"https://downloads.example.test/video.mp4\"}}");
        try {
            server.start();
            int port = server.getAddress().getPort();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", true, true,
                    "http://localhost:" + port + "/parse", false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(new AtomicInteger()));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("/parse?url=https%3A%2F%2Fv.douyin.com%2Fexample", requestUri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsupportedTestLinks() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        policies.saveDefaults(new AutomationPolicy("connection-a", "", false, true, "http://localhost:8080/parse", false,
                List.of(), List.of(), false, true, "", ""));
        MediaJobService service = new MediaJobService(policies, documents, framework(new AtomicInteger()));

        assertThrows(IllegalArgumentException.class,
                () -> service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://example.test/not-media")));
        assertFalse(documents.findById("media-job", "unknown").isPresent());
    }

    @Test
    void recordsProviderErrorDetailsWhenParsingFails() throws Exception {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = responseServer("/parse", requestUri, 400,
                "{\"detail\":{\"message\":\"upstream parser rejected share URL\"}}");
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", true, true,
                    "http://localhost:" + server.getAddress().getPort() + "/parse", false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(new AtomicInteger()));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "FAILED".equals(job(documents, jobId).get("status")));
            assertEquals("Media provider HTTP 400: upstream parser rejected share URL", job(documents, jobId).get("error"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsDockerBusinessErrorReturnedWithHttpSuccess() throws Exception {
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = responseServer("/api/download", requestUri, 200,
                "{\"code\":400,\"message\":\"upstream parser rejected share URL\"}");
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(new AtomicInteger()));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "FAILED".equals(job(documents, jobId).get("status")));
            assertEquals("Media provider did not return a media file: upstream parser rejected share URL", job(documents, jobId).get("error"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientAwemeIdFetcherFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                writeJson(exchange, 200, "{\"code\":400,\"message\":\"AwemeIdFetcher temporary failure\"}");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/api/douyin/web/fetch_video_comments", exchange -> writeJson(exchange, 200, "{\"data\":{\"comments\":[]}}"));
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(new AtomicInteger()));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsParsedVideoToTheOriginatingGroup() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        AtomicReference<Map<String, Object>> forwardedPayload = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = responseServer("/api/download", requestUri, "media-content");
        try {
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = new MediaJobService(policies, documents, framework(sentMessages, sentRequest, null, forwardedPayload));
            PluginEvent event = new PluginEvent("", "message_receive", "milky", "user-a", "group-a", "https://v.douyin.com/example",
                    "", "", Map.of(), "", null, "connection-a", "self-a", "message-a");

            service.handle(event);

            await(() -> sentMessages.get() == 1);
            assertEquals("group-a", forwardedPayload.get().get("group_id"));
            List<?> message = (List<?>) forwardedPayload.get().get("message");
            Map<?, ?> forward = (Map<?, ?>) ((Map<?, ?>) message.getFirst()).get("data");
            Map<?, ?> media = (Map<?, ?>) ((List<?>) forward.get("messages")).getFirst();
            assertEquals("video", ((Map<?, ?>) ((List<?>) media.get("segments")).getFirst()).get("type"));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer responseServer(String path, AtomicReference<String> requestUri, String responseBody) throws IOException {
        return responseServer(path, requestUri, 200, responseBody);
    }

    private HttpServer responseServer(String path, AtomicReference<String> requestUri, int status, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            if (responseBody.startsWith("{")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private HttpServer douyinServer(int commentsStatus, String commentsBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/api/hybrid/video_data", exchange -> writeJson(exchange, 200,
                "{\"data\":{\"aweme_id\":\"aweme-123\",\"music\":{\"play_url\":{\"url_list\":[\"http://localhost:"
                        + server.getAddress().getPort() + "/audio.mp3\"]}}}}"));
        server.createContext("/api/douyin/web/fetch_video_comments", exchange -> writeJson(exchange, commentsStatus, commentsBody));
        server.createContext("/audio.mp3", exchange -> {
            byte[] audio = "audio-content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        return server;
    }

    private HttpServer douyinImageServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_aweme_images.zip\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        String metadata = """
                {"data":{"type":"image","aweme_id":"aweme-image","author":{"nickname":"图文作者"},
                "image_data":{"no_watermark_image_list":["https://image.example.test/one.jpg","https://image.example.test/two.jpg"]},
                "music":{"play_url":{"url_list":["https://audio.example.test/post.mp3"]}}}}
                """;
        server.createContext("/api/hybrid/video_data", exchange -> writeJson(exchange, 200, metadata));
        server.createContext("/api/douyin/web/fetch_video_comments", exchange -> writeJson(exchange, 200, "{\"data\":{\"comments\":[]}}"));
        return server;
    }

    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private FrameworkServices framework(AtomicInteger sentMessages) {
        return framework(sentMessages, null);
    }

    private FrameworkServices framework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest) {
        return framework(sentMessages, sentRequest, null);
    }

    private FrameworkServices framework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest,
                                        List<PluginMessageRequest> requests) {
        return framework(sentMessages, sentRequest, requests, null);
    }

    private FrameworkServices framework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest,
                                        List<PluginMessageRequest> requests, AtomicReference<Map<String, Object>> forwardedPayload) {
        PluginMessagingService messaging = (PluginMessagingService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingService.class}, (proxy, method, args) -> {
                    if ("connections".equals(method.getName())) {
                        return List.of(new PluginMessagingConnection("connection-a", "Connection A", "milky", "self-a"));
                    }
                    if (method.getName().startsWith("send")) {
                        sentMessages.incrementAndGet();
                        if (sentRequest != null && args != null && args.length > 0 && args[0] instanceof PluginMessageRequest request) {
                            sentRequest.set(request);
                        }
                        if (requests != null && args != null && args.length > 0 && args[0] instanceof PluginMessageRequest request) {
                            requests.add(request);
                        }
                        return CompletableFuture.completedFuture(new PluginMessageResult(List.of("message-a"), false, false));
                    }
                    return null;
                });
        PluginMessagingRawService rawMessaging = (PluginMessagingRawService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingRawService.class}, (proxy, method, args) -> {
                    if ("invoke".equals(method.getName())) {
                        sentMessages.incrementAndGet();
                        if (forwardedPayload != null && args != null && args.length > 2 && args[2] instanceof Map<?, ?> payload) {
                            Map<String, Object> copy = new HashMap<>();
                            payload.forEach((key, value) -> copy.put(String.valueOf(key), value));
                            forwardedPayload.set(copy);
                        }
                        return CompletableFuture.completedFuture(Map.of());
                    }
                    return null;
                });
        return (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "messaging" -> messaging;
                    case "messagingRaw" -> rawMessaging;
                    default -> null;
                });
    }

    private Map<String, Object> job(InMemoryDocuments documents, String id) {
        return documents.findById("media-job", id).orElseThrow();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for media job completion");
    }

    private void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }

    private void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override
        public synchronized Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(key(collection, id), copy);
            return copy;
        }

        @Override
        public synchronized Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(key(collection, id))).map(HashMap::new);
        }

        @Override
        public synchronized List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> records = new ArrayList<>();
            values.forEach((key, value) -> {
                if (key.startsWith(collection + ":")) records.add(new HashMap<>(value));
            });
            int from = Math.min(Math.max(page - 1, 0) * size, records.size());
            int to = Math.min(from + size, records.size());
            return List.copyOf(records.subList(from, to));
        }
        @Override public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) { return List.of(); }
        @Override public synchronized long count(String collection) { return values.keySet().stream().filter(key -> key.startsWith(collection + ":")).count(); }
        @Override public synchronized void delete(String collection, String id) { values.remove(key(collection, id)); }

        private String key(String collection, String id) {
            return collection + ":" + id;
        }
    }
}
