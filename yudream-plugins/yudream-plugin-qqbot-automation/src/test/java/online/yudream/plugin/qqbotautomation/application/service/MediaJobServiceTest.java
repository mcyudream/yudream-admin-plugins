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
            MediaJobService service = service(policies, documents, framework(sentMessages, sentRequest, null, forwardedPayload));

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
    void downloadsBilibiliThroughProviderMetadataAndLocalCdnDownload() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> metadataUri = new AtomicReference<>();
        server.createContext("/api/bilibili/web/fetch_one_video", exchange -> {
            metadataUri.set(exchange.getRequestURI().toString());
            writeJson(exchange, 200, "{\"code\":200,\"data\":{\"code\":0,\"data\":{\"cid\":40009337567}}}");
        });
        server.createContext("/cdn/video.mp4", exchange -> {
            byte[] video = "bilibili-video-content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, video.length);
            exchange.getResponseBody().write(video);
            exchange.close();
        });
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        String playUrlEndpointPrevious = System.getProperty("YUDREAM_QQBOT_BILIBILI_PLAYURL_ENDPOINT");
        try {
            server.start();
            int port = server.getAddress().getPort();
            server.createContext("/playurl", exchange -> writeJson(exchange, 200,
                    "{\"code\":0,\"data\":{\"durl\":[{\"url\":\"http://localhost:" + port + "/cdn/video.mp4\"}]}}"));
            System.setProperty("YUDREAM_QQBOT_BILIBILI_PLAYURL_ENDPOINT", "http://localhost:" + port + "/playurl");
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + port, false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents, framework(sentMessages, sentRequest), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://www.bilibili.com/video/BV1kFKG6pEvU"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("/api/bilibili/web/fetch_one_video?bv_id=BV1kFKG6pEvU", metadataUri.get());
            assertEquals("file:///media/bilibili_video/bilibili_BV1kFKG6pEvU.mp4", sentRequest.get().content().content());
            assertEquals("bilibili-video-content", Files.readString(mediaDirectory.resolve("bilibili_video/bilibili_BV1kFKG6pEvU.mp4")));
        } finally {
            restoreProperty("YUDREAM_QQBOT_BILIBILI_PLAYURL_ENDPOINT", playUrlEndpointPrevious);
            server.stop(0);
            deleteTree(mediaDirectory);
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
            MediaJobService service = service(policies, documents, framework(sentMessages, null, requests, forwardedPayload));

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
            MediaJobService service = service(policies, documents, framework(sentMessages));

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
        try {
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/milky-media");
            server.start();
            policies.saveDefaults(new AutomationPolicy("connection-a", "", false, false,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents, framework(sentMessages, null, requests, forwardedPayload), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> sentMessages.get() == 2 && "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("forward", ((Map<?, ?>) ((List<?>) forwardedPayload.get().get("message")).getFirst()).get("type"));
            assertEquals(1, requests.size());
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.AUDIO, requests.getFirst().content().type());
            assertTrue(requests.getFirst().content().content().startsWith("file:///milky-media/douyin_audio/"));
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void pagesNewestJobsFirstAndClearsAllJobs() {
        InMemoryDocuments documents = new InMemoryDocuments();
        MediaJobService service = service(new AutomationPolicyService(documents), documents, framework(new AtomicInteger()));
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
            MediaJobService service = service(policies, documents, framework(sentMessages, null, requests, forwardedPayload));

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
            MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

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
        MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

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
            MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

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
            MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

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
            MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

            String jobId = service.startTest(new MediaJobTestRequest("connection-a", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientDouyinRiskControlForbidden() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                writeJson(exchange, 200, "{\"code\":400,\"message\":\"HTTP状态错误: 403\"}");
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
            MediaJobService service = service(policies, documents, framework(new AtomicInteger()));

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
            MediaJobService service = service(policies, documents, framework(sentMessages, sentRequest, null, forwardedPayload));
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

    @Test
    void officialConnectionPublishesDownloadedFileAsSignedUrl() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.writeString(video.resolve("douyin_7663032596767428986.mp4"), "official-video");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals("https://files.example.test/official.mp4", sentRequest.get().content().content());
            assertTrue(storedKey.get().startsWith("official/"));
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.VIDEO, sentRequest.get().content().type());
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialConnectionSendsSignedPreviewUrlEvenWithCallbackBase() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.writeString(video.resolve("douyin_7663032596767428986.mp4"), "official-video");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey,
                            "/api/public/preview/file/token/video.mp4", "https://admin.example.test/"), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals("/api/public/preview/file/token/video.mp4", sentRequest.get().content().content());
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.VIDEO, sentRequest.get().content().type());
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialConnectionSendsRelativeSignedUrlWithoutCallbackBase() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.writeString(video.resolve("douyin_7663032596767428986.mp4"), "official-video");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey,
                            "/api/public/preview/file/token/video.mp4", ""), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals("/api/public/preview/file/token/video.mp4", sentRequest.get().content().content());
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialConnectionSendsSignedUrlForLargeVideoWithoutPublicUrl() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.write(video.resolve("douyin_7663032596767428986.mp4"), new byte[256 * 1024]);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey,
                            "/api/public/preview/file/token/video.mp4", ""), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals("/api/public/preview/file/token/video.mp4", sentRequest.get().content().content());
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialConnectionSendsTopTenCommentsAsMarkdown() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        List<PluginMessageRequest> requests = new CopyOnWriteArrayList<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.writeString(video.resolve("douyin_7663032596767428986.mp4"), "official-video");
        StringBuilder comments = new StringBuilder("{\"data\":{\"comments\":[");
        comments.append("{\"text\":\"\",\"user\":{\"nickname\":\"表情用户\"},\"sticker\":{\"static_url\":{\"url_list\":[\"https://cdn.example.test/sticker.png\"]}}},");
        for (int index = 1; index <= 12; index++) {
            if (index > 1) {
                comments.append(',');
            }
            comments.append("{\"text\":\"评论").append(index).append("\",\"user\":{\"nickname\":\"用户").append(index).append("\"}}");
        }
        comments.append("]}}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/api/douyin/web/fetch_video_comments", exchange -> writeJson(exchange, 200, comments.toString()));
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/media");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, null, storedKey,
                            "/api/public/preview/file/token/video.mp4", "", requests), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> sentMessages.get() >= 2 && "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals(2, requests.size());
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.VIDEO, requests.get(0).content().type());
            assertEquals(online.yudream.base.plugin.spi.system.messaging.PluginMessageContent.Type.MARKDOWN, requests.get(1).content().type());
            String markdown = requests.get(1).content().content();
            assertTrue(markdown.startsWith("### 评论区（前 10 条）"), markdown);
            assertTrue(markdown.contains("**用户1**：评论1"), markdown);
            assertTrue(markdown.contains("**用户10**：评论10"), markdown);
            assertFalse(markdown.contains("评论11"), markdown);
            assertFalse(markdown.contains("表情用户"), markdown);
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialCommentsMarkdownTakesFirstTenTextComments() {
        List<Map<String, Object>> comments = new ArrayList<>();
        comments.add(Map.of("sender_name", "表情用户", "segments", List.of(Map.of("type", "image", "data", Map.of("uri", "https://cdn.example.test/a.png")))));
        for (int index = 1; index <= 12; index++) {
            comments.add(Map.of("sender_name", "用户" + index,
                    "segments", List.of(Map.of("type", "text", "data", Map.of("text", "评论" + index)))));
        }
        String markdown = MediaJobService.officialCommentsMarkdown(comments);
        assertTrue(markdown.startsWith("### 评论区（前 10 条）"));
        assertTrue(markdown.contains("**用户10**：评论10"));
        assertFalse(markdown.contains("评论11"));
        assertFalse(markdown.contains("表情用户"));
    }

    @Test
    void officialConnectionWaitsAndDecodesSharedFilename() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        String filename = "douyin_测试视频.mp4";
        String encodedFilename = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"douyin_video.mp4\"; filename*=UTF-8''" + encodedFilename);
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(400);
                    Files.writeString(video.resolve(filename), "delayed-official-video");
                } catch (Exception ignored) {
                }
            });
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save(mediaDirectory.toString(), "/app/download");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey), mediaSettings);

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));

            await(() -> "COMPLETED".equals(job(documents, jobId).get("status")));
            assertEquals("COMPLETED", job(documents, jobId).get("status"), String.valueOf(job(documents, jobId).get("error")));
            assertEquals("https://files.example.test/official.mp4", sentRequest.get().content().content());
            assertTrue(storedKey.get().contains("douyin_测试视频.mp4"));
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
        }
    }

    @Test
    void officialConnectionFindsFileOnBackendMediaMountWhenHostSettingIsWrong() throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        AtomicReference<PluginMessageRequest> sentRequest = new AtomicReference<>();
        InMemoryDocuments documents = new InMemoryDocuments();
        AutomationPolicyService policies = new AutomationPolicyService(documents);
        AtomicReference<String> storedKey = new AtomicReference<>();
        Path mediaDirectory = Files.createTempDirectory("qqbot-milky-media-");
        Path video = mediaDirectory.resolve("douyin_video");
        Files.createDirectories(video);
        Files.writeString(video.resolve("douyin_7663032596767428986.mp4"), "backend-mount-video");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"douyin_7663032596767428986.mp4\"");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        try {
            server.start();
            MediaStorageSettings mediaSettings = new MediaStorageSettings(new InMemorySecrets());
            mediaSettings.save("/opt/yudream-douyin-api/download", "/app/download");
            policies.saveDefaults(new AutomationPolicy("connection-official", "", true, true,
                    "http://localhost:" + server.getAddress().getPort(), false, List.of(), List.of(), false, true, "", ""));
            MediaJobService service = service(policies, documents,
                    officialFramework(sentMessages, sentRequest, storedKey), mediaSettings);
            List<Path> roots = service.mediaReadRoots("/opt/yudream-douyin-api/download", "/app/download");
            assertTrue(roots.stream().anyMatch(path -> path.toString().replace('\\', '/').endsWith("/media") || path.toString().equals("/media")));
            assertTrue(roots.stream().anyMatch(path -> path.toString().replace('\\', '/').contains("opt")));

            String jobId = service.startTest(new MediaJobTestRequest("connection-official", "group-a", "https://v.douyin.com/example"));
            await(() -> "FAILED".equals(job(documents, jobId).get("status")) || "COMPLETED".equals(job(documents, jobId).get("status")));
            String error = String.valueOf(job(documents, jobId).get("error"));
            assertTrue(error.contains("tried=") || "COMPLETED".equals(job(documents, jobId).get("status")), error);
            assertTrue(error.contains("/media") || error.contains("\\media") || "COMPLETED".equals(job(documents, jobId).get("status")), error);
        } finally {
            server.stop(0);
            deleteTree(mediaDirectory);
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

    private FrameworkServices officialFramework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest,
                                                AtomicReference<String> storedKey) {
        return officialFramework(sentMessages, sentRequest, storedKey, "https://files.example.test/official.mp4", "");
    }

    private FrameworkServices officialFramework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest,
                                                AtomicReference<String> storedKey, String signedUrl, String callbackBaseUrl) {
        return officialFramework(sentMessages, sentRequest, storedKey, signedUrl, callbackBaseUrl, null);
    }

    private FrameworkServices officialFramework(AtomicInteger sentMessages, AtomicReference<PluginMessageRequest> sentRequest,
                                                AtomicReference<String> storedKey, String signedUrl, String callbackBaseUrl,
                                                List<PluginMessageRequest> requests) {
        PluginMessagingService messaging = (PluginMessagingService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingService.class}, (proxy, method, args) -> {
                    if ("connections".equals(method.getName())) {
                        return List.of(new PluginMessagingConnection("connection-official", "Official", "official", "self-a", "official"));
                    }
                    if (method.getName().startsWith("send")) {
                        sentMessages.incrementAndGet();
                        if (args != null && args.length > 0 && args[0] instanceof PluginMessageRequest request) {
                            if (sentRequest != null) {
                                sentRequest.set(request);
                            }
                            if (requests != null) {
                                requests.add(request);
                            }
                        }
                        return CompletableFuture.completedFuture(new PluginMessageResult(List.of("message-a"), false, false));
                    }
                    return null;
                });
        PluginMessagingRawService rawMessaging = (PluginMessagingRawService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingRawService.class}, (proxy, method, args) -> CompletableFuture.completedFuture(Map.of()));
        online.yudream.base.plugin.spi.system.storage.PluginFileStore files = (online.yudream.base.plugin.spi.system.storage.PluginFileStore)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{online.yudream.base.plugin.spi.system.storage.PluginFileStore.class}, (proxy, method, args) -> {
                            if ("put".equals(method.getName())) {
                                storedKey.set(String.valueOf(args[0]));
                                return args[0];
                            }
                            return null;
                        });
        online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService preview =
                (online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService.class}, (proxy, method, args) -> {
                            if ("signedFileUrl".equals(method.getName())) return signedUrl;
                            if ("callbackBaseUrl".equals(method.getName())) return callbackBaseUrl;
                            return null;
                        });
        return (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "messaging" -> messaging;
                    case "messagingRaw" -> rawMessaging;
                    case "files" -> files;
                    case "filePreview" -> preview;
                    default -> null;
                });
    }

    private MediaJobService service(AutomationPolicyService policies, InMemoryDocuments documents, FrameworkServices framework) {
        return service(policies, documents, framework, new MediaStorageSettings(new InMemorySecrets()));
    }

    private MediaJobService service(AutomationPolicyService policies, InMemoryDocuments documents, FrameworkServices framework,
                                    MediaStorageSettings mediaSettings) {
        return new MediaJobService(policies, documents, framework, mediaSettings);
    }

    private Map<String, Object> job(InMemoryDocuments documents, String id) {
        return documents.findById("media-job", id).orElseThrow();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 12_000_000_000L;
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

    private static final class InMemorySecrets implements online.yudream.base.plugin.spi.system.secret.PluginSecretStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public void put(String key, byte[] secret) { values.put(key, secret); }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public boolean delete(String key) { return values.remove(key) != null; }
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
