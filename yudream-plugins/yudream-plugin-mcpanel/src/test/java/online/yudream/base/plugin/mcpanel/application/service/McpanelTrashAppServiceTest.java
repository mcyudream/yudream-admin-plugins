package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.AuditRecorder;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.NodeCallGateway;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.TrashRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentTrashRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回收站应用服务单测：快照落库、装饰分页、找回重建、离线保留记录。 */
class McpanelTrashAppServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final PortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());
    private final TrashRepository trashRepo = new DocumentTrashRepository(documents, McpanelJson.mapper());
    private final Map<String, Object> createdPayload = new ConcurrentHashMap<>();
    private final List<String> auditCalls = new java.util.ArrayList<>();

    private McpanelNode onlineNode() {
        McpanelNode node = McpanelNode.create("node-1", "节点一", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L);
        nodes.save(node.withReported("0.4.0", "h", "a".repeat(64), 1L));
        nodes.save(nodes.findById("node-1").orElseThrow().withRuntime(
                NodeStatus.ONLINE, "0.4.0", "h", "sess",
                List.of("instance.purge", "trash.list", "trash.delete"), "27", null, 1L, 1L));
        return nodes.findById("node-1").orElseThrow();
    }

    private McpanelInstanceAppService instanceService(NodeCallGateway gateway) {
        return new McpanelInstanceAppService(instances, nodes, ports, gateway,
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) ->
                        auditCalls.add(action + ":" + targetId),
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey,
                                             online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new MinecraftLinkService(java.util.Optional::empty), null);
    }

    private McpanelTrashAppService trashService(McpanelInstanceAppService instanceService,
                                                NodeCallGateway trashGateway) {
        return new McpanelTrashAppService(trashRepo, nodes, instanceService, trashGateway,
                (actor, action, targetType, targetId, detail, tenantId) ->
                        auditCalls.add(action + ":" + targetId),
                McpanelJson.mapper());
    }

    private McpanelInstanceAppService instanceServiceForSetup() {
        return instanceService((nodeId, method, payload) -> {
            if ("instance.create".equals(method)) {
                createdPayload.putAll(payload);
                return CompletableFuture.completedFuture(Map.of("state", "created"));
            }
            if ("instance.delete".equals(method)) {
                return CompletableFuture.completedFuture(Map.of("deleted", true, "trashed", true));
            }
            return CompletableFuture.completedFuture(Map.of());
        });
    }

    private String createAndDeleteToTrash(McpanelInstanceAppService service, McpanelTrashAppService trash) {
        service.attachTrashSink(trash::record);
        service.create("user:1", "user:1", McpanelInstance.create("inst-x", "node-1", "生存服",
                "paper", "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"),
                Map.of(), 1024, 1000, 2048, List.of(), Map.of(), null, "备注", 1L).withBinding(null, 1L));
        service.delete("user:1", "user:1", "inst-x", false);
        return "inst-x";
    }

    @Test
    void deleteSnapshotLandsInTrashAndPageShowsIt() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = trashService(service,
                (nodeId, method, payload) -> CompletableFuture.completedFuture(Map.of()));
        String trashId = createAndDeleteToTrash(service, trash);

        assertTrue(trashRepo.findById(trashId).isPresent());
        Map<String, Object> page = trash.page(1, 10, null);
        assertEquals(1L, page.get("total"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) page.get("records")).get(0);
        assertEquals("inst-x", row.get("trashId"));
        assertEquals("生存服", row.get("instanceName"));
        assertEquals("节点一", row.get("nodeName"));
        assertEquals(Boolean.TRUE, row.get("nodeOnline"));
        assertEquals(Boolean.TRUE, row.get("trashCapable"));
        // 节点网关未实现 trash.list（返回空）：目录在位性与保留期应为未知而非报错。
        assertNull(row.get("dirPresent"));
        // 快照应完整保存规格（找回时还原）。
        assertEquals("paper", trashRepo.findById(trashId).orElseThrow().specSnapshot().get("kind"));
    }

    @Test
    void pageDecoratesDirPresenceAndRetentionFromNode() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = trashService(service,
                (nodeId, method, payload) -> {
                    if ("trash.list".equals(method)) {
                        return CompletableFuture.completedFuture(Map.of(
                                "retentionDays", 14,
                                "items", List.of(Map.of("trashId", "inst-x",
                                        "instanceId", "inst-x", "name", "生存服", "deletedAt", 1L))));
                    }
                    return CompletableFuture.completedFuture(Map.of());
                });
        String trashId = createAndDeleteToTrash(service, trash);
        assertEquals("inst-x", trashId);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) trash.page(1, 10, null).get("records")).get(0);
        assertEquals(Boolean.TRUE, row.get("dirPresent"));
        assertEquals("inst-x", row.get("nodeTrashId"));
        assertEquals(14, row.get("retentionDays"));
    }

    @Test
    void restoreRecreatesInstanceWithFromTrashAndClearsRecord() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = trashService(service,
                (nodeId, method, payload) -> CompletableFuture.completedFuture(Map.of()));
        String trashId = createAndDeleteToTrash(service, trash);

        createdPayload.clear();
        Map<String, Object> restored = trash.restore("user:9", "user:9", trashId, "生存服二号");

        assertEquals("created", restored.get("state"));
        assertEquals("inst-x", restored.get("id"));
        // 找回 = 以原实例 id 重建 + 节点侧 fromTrash 恢复目录 + 名称可改。
        assertEquals("inst-x", createdPayload.get("instanceId"));
        assertEquals("inst-x", createdPayload.get("fromTrash"));
        assertTrue(trashRepo.findById(trashId).isEmpty());
        assertTrue(auditCalls.stream().anyMatch(call -> call.startsWith("trash.restore:inst-x")));
    }

    @Test
    void restoreRejectsMissingRecord() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = trashService(service,
                (nodeId, method, payload) -> CompletableFuture.completedFuture(Map.of()));
        assertThrows(McpanelBusinessException.class,
                () -> trash.restore("user:9", "user:9", "inst-none", null));
    }

    @Test
    void restoreKeepsRecordWhenCreateFails() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = trashService(service,
                (nodeId, method, payload) -> CompletableFuture.completedFuture(Map.of()));
        String trashId = createAndDeleteToTrash(service, trash);
        // 同节点先占住同名实例：找回必须因名称冲突失败，且回收站记录保留以便改名重试。
        service.create("user:1", "user:1", McpanelInstance.create("inst-y", "node-1", "生存服",
                "paper", "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"),
                Map.of(), 1024, 1000, 2048, List.of(), Map.of(), null, "", 1L).withBinding(null, 1L));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> trash.restore("user:9", "user:9", trashId, "生存服"));
        assertEquals("instance.name-conflict", error.code());
        assertTrue(trashRepo.findById(trashId).isPresent());
    }

    @Test
    void removeProxiesTrashDeleteAndClearsRecord() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        List<String> nodeMethods = new java.util.ArrayList<>();
        McpanelTrashAppService trash = new McpanelTrashAppService(trashRepo, nodes, service,
                (nodeId, method, payload) -> {
                    nodeMethods.add(method);
                    return CompletableFuture.completedFuture(Map.of("deleted", true));
                },
                (actor, action, targetType, targetId, detail, tenantId) ->
                        auditCalls.add(action + ":" + targetId),
                McpanelJson.mapper());
        service.attachTrashSink(trash::record);
        service.create("user:1", "user:1", McpanelInstance.create("inst-rm", "node-1", "临时服",
                "vanilla", "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"),
                Map.of(), 1024, 1000, 2048, List.of(), Map.of(), null, "", 1L).withBinding(null, 1L));
        service.delete("user:1", "user:1", "inst-rm", false);

        Map<String, Object> result = trash.remove("user:1", "inst-rm");

        assertEquals(Boolean.TRUE, result.get("deleted"));
        assertTrue(nodeMethods.contains("trash.delete"));
        assertTrue(trashRepo.findById("inst-rm").isEmpty());
        assertTrue(auditCalls.stream().anyMatch(call -> call.startsWith("trash.delete:inst-rm")));
    }

    @Test
    void removeKeepsRecordWhenNodeOffline() {
        onlineNode();
        McpanelInstanceAppService service = instanceServiceForSetup();
        McpanelTrashAppService trash = new McpanelTrashAppService(trashRepo, nodes, service,
                (nodeId, method, payload) -> CompletableFuture.failedFuture(
                        new NodeCallException("node.offline", "节点未在线")),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                McpanelJson.mapper());
        service.attachTrashSink(trash::record);
        service.create("user:1", "user:1", McpanelInstance.create("inst-off", "node-1", "离线服",
                "vanilla", "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"),
                Map.of(), 1024, 1000, 2048, List.of(), Map.of(), null, "", 1L).withBinding(null, 1L));
        service.delete("user:1", "user:1", "inst-off", false);

        Map<String, Object> result = trash.remove("user:1", "inst-off");

        // 节点离线时数据无法销毁：记录必须保留，错误透传给前端。
        assertEquals(Boolean.FALSE, result.get("nodeRemoved"));
        assertNotNull(result.get("error"));
        assertTrue(trashRepo.findById("inst-off").isPresent());
    }
}
