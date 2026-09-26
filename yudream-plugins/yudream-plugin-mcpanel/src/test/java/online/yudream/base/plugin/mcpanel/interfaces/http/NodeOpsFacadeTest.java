package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 节点操作 facade 回归：超时/离线/能力错误的人话映射、SFTP 审计接入、未就绪节点拒绝。 */
class NodeOpsFacadeTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());

    @BeforeEach
    void seedNode() {
        documents.clear();
        nodes.save(McpanelNode.create("node-1", "节点", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L)
                .withReported("0.2.0", "h", "a".repeat(64), 1L));
    }

    private NodeOpsFacade facade(NodeControlPlane plane, McpanelInstanceAppService.AuditRecorder audit) {
        return new NodeOpsFacade(nodes, plane,
                (nodeId, eventType, matchKey, matchValue) -> null, audit);
    }

    /** 固定应答/固定异常的控面桩（failure 直接作为 future 异常，facade 经 get() 收到 ExecutionException）。 */
    private NodeControlPlane plane(java.util.function.Function<String, Map<String, Object>> answer,
                                   Throwable failure) {
        return new NodeControlPlane() {
            @Override
            public void syncNode(McpanelNode node) {
            }

            @Override
            public void removeNode(String nodeId) {
            }

            @Override
            public NodeRuntime runtime(String nodeId) {
                return NodeRuntime.empty();
            }

            @Override
            public PluginSseStream openEventStream(String nodeId, Long afterEventId) {
                return null;
            }

            @Override
            public CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                               Map<String, Object> payload) {
                return call(nodeId, method, payload, 30_000L);
            }

            @Override
            public CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                               Map<String, Object> payload, long timeoutMs) {
                if (failure != null) {
                    return CompletableFuture.failedFuture(failure);
                }
                return CompletableFuture.completedFuture(answer.apply(method));
            }

            @Override
            public List<String> caps(String nodeId) {
                return List.of();
            }
        };
    }

    @Test
    void timeoutMapsTo504WithHumanMessage() {
        NodeOpsFacade facade = facade(plane(null, new TimeoutException("future orTimeout")), null);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> facade.nodeFile("node-1", "list", Map.of()));
        assertEquals("node.timeout", error.code());
        assertEquals(504, error.httpStatus());
        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    void offlineMapsTo502() {
        NodeOpsFacade facade = facade(plane(null,
                new NodeCallException("node.offline", "节点未在线")), null);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> facade.nodeFile("node-1", "write", Map.of("path", "/etc", "content", "x")));
        assertEquals("node.offline", error.code());
        assertEquals(502, error.httpStatus());
        assertTrue(error.getMessage().contains("节点未在线"));
    }

    @Test
    void capabilityMapsTo409() {
        NodeOpsFacade facade = facade(plane(null,
                new NodeCallException("node.capability", "节点不支持 node.file.write")), null);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> facade.nodeFile("node-1", "write", Map.of("path", "/etc", "content", "x")));
        assertEquals("node.capability", error.code());
        assertEquals(409, error.httpStatus());
        assertTrue(error.getMessage().contains("不支持"));
    }

    @Test
    void nodeBusinessErrorPassesThroughAt500() {
        NodeOpsFacade facade = facade(plane(null,
                new NodeCallException("file.denied", "路径不在白名单")), null);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> facade.nodeFile("node-1", "write", Map.of("path", "/etc", "content", "x")));
        assertEquals("file.denied", error.code());
        assertEquals(500, error.httpStatus());
        assertEquals("路径不在白名单", error.getMessage());
    }

    @Test
    void ftpOpenRecordsAuditWithPlatformTenant() {
        AtomicReference<String> captured = new AtomicReference<>();
        McpanelInstanceAppService.AuditRecorder recorder =
                (actor, action, targetType, targetId, detail, tenantId) ->
                        captured.set(actor + "|" + action + "|" + targetType + "|" + targetId
                                + "|" + tenantId);
        NodeOpsFacade facade = facade(plane(method -> Map.of("user", "u1", "port", 2022), null),
                recorder);
        Map<String, Object> result = facade.nodeFtpOpen("user:7", "node-1", Map.of("root", "/srv"));
        assertEquals("u1", result.get("user"));
        assertEquals("user:7|node.ftp.open|node|node-1|", captured.get());
    }

    @Test
    void terminalEventsRejectsBlankTerminalId() {
        NodeOpsFacade facade = facade(plane(method -> Map.of(), null), null);
        // 缺 terminalId：直接 400，不向事件总线订阅空过滤流（节点认证不受影响）。
        McpanelBusinessException blank = assertThrows(McpanelBusinessException.class,
                () -> facade.terminalEvents("node-1", "  "));
        assertEquals("invalid-request", blank.code());
        assertEquals(400, blank.httpStatus());
        McpanelBusinessException missing = assertThrows(McpanelBusinessException.class,
                () -> facade.terminalEvents("node-1", null));
        assertEquals("invalid-request", missing.code());
    }

    @Test
    void terminalCompleteRelaysToNodeOp() {
        AtomicReference<String> method = new AtomicReference<>();
        NodeControlPlane capturing = plane(m -> {
            method.set(m);
            return Map.of("completions", java.util.List.of("docker", "docker-compose"));
        }, null);
        NodeOpsFacade facade = new NodeOpsFacade(nodes, capturing,
                (nodeId, eventType, matchKey, matchValue) -> null, null);
        Map<String, Object> result = facade.terminalComplete("node-1", Map.of("prefix", "doc", "limit", 10));
        assertEquals("node.term.complete", method.get());
        assertEquals(java.util.List.of("docker", "docker-compose"), result.get("completions"));
    }

    @Test
    void nodeNotReadyRejected() {
        // 停用节点：requireEnabledNode 在任何节点调用前拒绝。
        McpanelNode disabled = McpanelNode.create("node-2", "停用节点", "wss://127.0.0.1:9702",
                "pinned", "b".repeat(64), true, "test", false, 1L)
                .withReported("0.2.0", "h", "b".repeat(64), 1L);
        nodes.save(disabled);
        NodeOpsFacade facade = facade(plane(method -> Map.of(), null), null);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> facade.terminalOpen("user:1", "node-2", Map.of()));
        assertEquals("node-not-ready", error.code());
        assertEquals(409, error.httpStatus());
    }
}
