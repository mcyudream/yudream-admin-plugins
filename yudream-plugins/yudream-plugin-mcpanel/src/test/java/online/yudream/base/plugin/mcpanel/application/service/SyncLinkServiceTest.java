package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子服软链接：建链校验、同步链路（zip→分块→上传→解压）、overrides 与双向清理。 */
class SyncLinkServiceTest {

    /** 顺序记录调用的文件网关；download.chunk 按脚本回包。 */
    private static final class RecordingGateway implements SyncLinkService.FilesGateway {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, Object>> callArgs = new ArrayList<>();
        final List<String> chunkPayloads = new ArrayList<>();

        @Override
        public Map<String, Object> invoke(String scopeKey, String instanceId, String method, Map<String, Object> args) {
            calls.add(method + ":" + instanceId);
            callArgs.add(new LinkedHashMap<>(args));
            Map<String, Object> result = new LinkedHashMap<>();
            if ("download.chunk".equals(method)) {
                if (chunkPayloads.isEmpty()) {
                    result.put("content", "");
                }
                else {
                    result.put("content", chunkPayloads.remove(0));
                }
            }
            return result;
        }
    }

    private static McpanelInstance instance(String id, String nodeId, String name, int port) {
        return McpanelInstance.create(id, nodeId, name, "paper", "1.21", "", "img",
                List.of("java", "-jar", "server.jar"), Map.of(), 512, 500, 1024,
                port <= 0 ? List.of() : List.of(new McpanelInstance.PortMapping(port, port, "tcp")),
                Map.of(), null, "", System.currentTimeMillis());
    }

    private static SyncLinkService service(InMemoryDocumentStore documents,
                                           McpanelInstanceRepository instances,
                                           RecordingGateway gateway,
                                           List<String> uploads) {
        return new SyncLinkService(documents, instances,
                new DocumentNodeRepository(documents, McpanelJson.mapper()), gateway,
                (actor, scopeKey, instanceId, name, bytes) -> uploads.add(name + ":" + bytes.length),
                null);
    }

    @Test
    void linkValidatesAndFallsBackToDefaultPaths() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        instances.save(instance("src", "nodeA", "模板服", 25565));
        instances.save(instance("dst", "nodeA", "子服1", 25566));
        SyncLinkService service = service(documents, instances, new RecordingGateway(), new ArrayList<>());

        assertThrows(McpanelBusinessException.class, () -> service.link("u", "dst", "dst", List.of()));
        assertThrows(McpanelBusinessException.class, () -> service.link("u", "dst", "src", List.of("../etc")));
        Map<String, Object> linked = service.link("u", "dst", "src", List.of());
        assertTrue(((List<?>) linked.get("paths")).size() > 5, "空清单回落默认同步清单");
        assertEquals("模板服", linked.get("sourceName"));
        assertNotNull(service.linkOf("dst"));
    }

    @Test
    void syncNowStreamsBundleAndUnzips() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        instances.save(instance("src", "nodeA", "模板服", 25565));
        instances.save(instance("dst", "nodeA", "子服1", 25566));
        RecordingGateway gateway = new RecordingGateway();
        byte[] zipBytes = "PK-fake-bundle-bytes".getBytes(StandardCharsets.UTF_8);
        gateway.chunkPayloads.add(Base64.getEncoder().encodeToString(zipBytes));
        List<String> uploads = new ArrayList<>();
        SyncLinkService service = service(documents, instances, gateway, uploads);

        service.link("u", "dst", "src", List.of("plugins"));
        Map<String, Object> result = service.syncNow("u", "user:1", "dst");
        assertEquals(zipBytes.length, result.get("bytes"));
        assertEquals("mcpanel-sync-bundle.zip:" + zipBytes.length, uploads.get(0));
        // 顺序：源 zip → 源 download.chunk → 源 delete → 目标 upload → 目标 unzip → 目标 delete。
        assertEquals(List.of(
                "zip:src", "download.chunk:src", "delete:src",
                "unzip:dst", "delete:dst"), gateway.calls);
        assertEquals(List.of("plugins"), gateway.callArgs.get(0).get("paths"));
        assertTrue(((Number) service.linkOf("dst").get("lastSyncAt")).longValue() > 0);
    }

    @Test
    void evictClearsBothDirections() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        instances.save(instance("src", "nodeA", "模板服", 25565));
        instances.save(instance("dst", "nodeA", "子服1", 25566));
        SyncLinkService service = service(documents, instances, new RecordingGateway(), new ArrayList<>());
        service.link("u", "dst", "src", List.of("plugins"));
        // 源被删除：作为源的链接一并失效。
        service.evict("src");
        org.junit.jupiter.api.Assertions.assertNull(service.linkOf("dst"));
    }
}
