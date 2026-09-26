package online.yudream.base.plugin.mcpanel.infrastructure;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeConnectionManager;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeProtocol;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实回环 WS 传输集成（协议 v1 §3/§5.1/§5.2）：
 * 握手头校验 → node.hello 往返 → 立即 stats → 断开重连 → 超大帧 1009 关闭
 * → 非法 hello/空 stats 不续命 → 停用拨号 race。
 * 非 mock：走 java.net.http 真实 WebSocket 客户端栈（明文 ws 仅限测试回环，
 * 生产 endpoint 由 NodeEndpointPolicy 强制 wss）。
 */
class NodeConnectionManagerIntegrationTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final InMemorySecretStore secretStore = new InMemorySecretStore();
    private final McpanelNodeRepository repository =
            new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final NodeSecrets secrets = new NodeSecrets(secretStore);
    private final NodeEventBus eventBus = new NodeEventBus();
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

    private FakeNodeServer server;
    private NodeConnectionManager manager;
    private McpanelNode node;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (manager != null) {
            manager.close();
        }
        if (server != null) {
            server.stop(500);
        }
    }

    @Test
    void helloHandshakeStatsAndReconnectOverRealTransport() throws Exception {
        server = new FakeNodeServer("node-x", "secret-x");
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-x", "secret-x");

        // hello 往返 + 立即 stats → ONLINE + 持久化
        await("hello received", () -> server.helloCount.get() >= 1);
        // 握手头：Authorization Bearer + X-Mcpanel-Node-Id（协议 §3），hello 后再断言
        assertEquals("Bearer secret-x", server.seenAuthorization.poll());
        assertEquals("node-x", server.seenNodeId.poll());
        await("node online", () -> repository.findById("node-x").map(McpanelNode::online).orElse(false));
        await("stats persisted", () -> repository.findById("node-x")
                .map(n -> n.lastStats() != null).orElse(false));
        await("state event", () -> events.stream().anyMatch(e -> e.startsWith("node.state|") && e.contains("status=online")));
        await("stats event", () -> events.stream().anyMatch(e -> e.startsWith("node.stats|")));
        McpanelNode online = repository.findById("node-x").orElseThrow();
        assertEquals("0.1.0", online.agentVersion());
        assertEquals("node-x-host", online.reportedHost());
        assertEquals(List.of("node.hello", "node.stats"), online.caps());
        assertEquals("24.0.7", online.dockerVersion());

        // 服务端主动断开 → offline + 自动重连（1s 起退避）
        int connectionsBefore = server.connectionCount.get();
        server.lastConn().close(1000, "test close");
        await("offline after close",
                () -> events.stream().anyMatch(e -> e.startsWith("node.state|") && e.contains("status=offline")));
        await("reconnected", () -> server.connectionCount.get() >= connectionsBefore + 1
                && server.helloCount.get() >= 2);
        await("online again", () -> repository.findById("node-x").map(McpanelNode::online).orElse(false));
        assertEquals(NodeStatus.ONLINE.code(), repository.findById("node-x").orElseThrow().status());
    }

    @Test
    void oversizedFrameClosesConnectionAsBadFrame() throws Exception {
        server = new FakeNodeServer("node-big", "secret-big");
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-big", "secret-big");
        await("node online", () -> repository.findById("node-big").map(McpanelNode::online).orElse(false));
        server.lastConn().send("{\"v\":1,\"t\":\"evt\",\"m\":\"node.stats\",\"ts\":1,\"seq\":0,\"p\":{},"
                + "padding\":\"" + "x".repeat(NodeProtocol.MAX_FRAME_BYTES + 1024) + "\"}");
        await("offline after oversized frame",
                () -> events.stream().anyMatch(e -> e.startsWith("node.state|") && e.contains("status=offline")));
    }

    @Test
    void wrongSecretConnectionStillDialsButNodeRejectsHandshake() throws Exception {
        server = new FakeNodeServer("node-y", "expected-secret");
        server.rejectUnauthorized = true;
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-y", "wrong-secret");
        // 握手被节点拒绝：反复重试但节点永不 ONLINE。
        await("handshake attempts", () -> server.handshakeAttempts.get() >= 2);
        assertTrue(repository.findById("node-y").map(n -> !n.online()).orElse(true));
        assertTrue(events.stream().noneMatch(e -> e.startsWith("node.state|") && e.contains("status=online")));
    }

    @Test
    void invalidHelloPayloadNeverGoesOnline() throws Exception {
        server = new FakeNodeServer("node-h", "secret-h");
        server.invalidHello = true;
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-h", "secret-h");
        await("hello attempted", () -> server.helloCount.get() >= 1);
        await("retried after invalid hello", () -> server.helloCount.get() >= 2);
        assertTrue(repository.findById("node-h").map(n -> !n.online()).orElse(true));
        assertTrue(events.stream().noneMatch(e -> e.startsWith("node.state|") && e.contains("status=online")));
    }

    @Test
    void emptyStatsPayloadDoesNotRefreshOrPublish() throws Exception {
        server = new FakeNodeServer("node-s", "secret-s");
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-s", "secret-s");
        await("first valid stats", () -> countEvents("node.stats|") >= 1);
        long statsBefore = countEvents("node.stats|");
        NodeStatsSnapshotView before = viewLastStats("node-s");
        server.sendRawStats(new LinkedHashMap<>(), 1L);
        Thread.sleep(700);
        assertEquals(statsBefore, countEvents("node.stats|"), "空载荷 stats 不得产生事件");
        NodeStatsSnapshotView after = viewLastStats("node-s");
        assertEquals(before.reportedAt, after.reportedAt, "空载荷 stats 不得更新快照");
        assertTrue(repository.findById("node-s").map(McpanelNode::online).orElse(false),
                "合法在线不受坏帧影响");
    }

    @Test
    void disableDuringConnectingKeepsNodeOfflineAndStopsHello() throws Exception {
        server = new FakeNodeServer("node-d", "secret-d");
        server.start();
        awaitPort();
        connectNode("ws://127.0.0.1:" + server.getPort(), "node-d", "secret-d");
        // 拨号已入队/进行中，立即停用：后续 onOpen 必须被 generation 丢弃。
        McpanelNode disabled = repository.findById("node-d").orElseThrow()
                .withConfig("node-d", server.endpoint(), "pkix", null, true, null, false,
                        System.currentTimeMillis());
        manager.syncNode(disabled);
        Thread.sleep(2000);
        assertEquals(NodeStatus.OFFLINE.code(), repository.findById("node-d").orElseThrow().status());
        assertEquals(0, server.helloCount.get(), "停用后不得发送 hello");
    }

    // ---------- fixtures ----------

    private long countEvents(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    private NodeStatsSnapshotView viewLastStats(String nodeId) {
        return repository.findById(nodeId)
                .map(n -> n.lastStats() == null ? null : new NodeStatsSnapshotView(n.lastStats().reportedAtMs()))
                .orElse(new NodeStatsSnapshotView(0L));
    }

    private record NodeStatsSnapshotView(long reportedAt) {
    }

    private void connectNode(String endpoint, String nodeId, String secret) {
        secretStore.put(NodeSecrets.key(nodeId), secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        node = McpanelNode.create(nodeId, nodeId, endpoint, "pkix", null, true, null, true,
                System.currentTimeMillis());
        // 传输层测试模拟「已 bootstrap」节点：满足 dial 的 enrolled 门禁。
        node = node.withReported("0.1.0", nodeId + "-host", null, System.currentTimeMillis());
        repository.save(node);
        manager = new NodeConnectionManager(repository, secrets, eventBus, 15_000L, 5_000L, 50L);
        eventBus.subscribe(new online.yudream.base.plugin.spi.http.PluginSseStream.Subscriber() {
            @Override
            public void send(String event, Object payload) {
                events.add(event + "|" + String.valueOf(payload));
            }

            @Override
            public void complete() {
                // no-op
            }

            @Override
            public void error(Throwable error) {
                // no-op
            }
        });
        manager.start();
        manager.syncNode(node);
    }

    private void awaitPort() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(server.getPort() > 0, "server did not bind");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    /** 回环上的假节点：协议 §3/§5.1/§5.2 行为的服务端镜像。 */
    private static final class FakeNodeServer extends WebSocketServer {

        private final String expectedNodeId;
        private final String expectedSecret;
        final AtomicInteger helloCount = new AtomicInteger();
        final AtomicInteger connectionCount = new AtomicInteger();
        final AtomicInteger handshakeAttempts = new AtomicInteger();
        final ConcurrentLinkedQueue<String> seenAuthorization = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> seenNodeId = new ConcurrentLinkedQueue<>();
        volatile boolean rejectUnauthorized;
        volatile boolean invalidHello;
        volatile WebSocket activeConn;

        FakeNodeServer(String expectedNodeId, String expectedSecret) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.expectedNodeId = expectedNodeId;
            this.expectedSecret = expectedSecret;
            setConnectionLostTimeout(0);
        }

        String endpoint() {
            return "ws://127.0.0.1:" + getPort();
        }

        @Override
        public org.java_websocket.handshake.ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
                WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
            handshakeAttempts.incrementAndGet();
            seenAuthorization.add(request.getFieldValue("Authorization"));
            seenNodeId.add(request.getFieldValue("X-Mcpanel-Node-Id"));
            if (rejectUnauthorized) {
                throw new InvalidDataException(401, "bad secret");
            }
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            connectionCount.incrementAndGet();
            activeConn = conn;
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            Optional<NodeProtocol.Envelope> decoded = NodeProtocol.decode(message);
            if (decoded.isEmpty() || !"node.hello".equals(decoded.get().m())) {
                return;
            }
            helloCount.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", expectedNodeId);
            if (!invalidHello) {
                payload.put("sessionId", NodeProtocol.newUlid());
                payload.put("hostname", expectedNodeId + "-host");
                payload.put("agentVersion", "0.1.0");
                payload.put("caps", List.of("node.hello", "node.stats"));
                payload.put("dockerVersion", "24.0.7");
            }
            conn.send(NodeProtocol.encode(NodeProtocol.Envelope.response(decoded.get(), payload)));
            if (!invalidHello) {
                sendRawStats(Map.of(
                        "cpuPercent", 7.5, "memUsedMb", 128, "memTotalMb", 2048,
                        "diskUsedGb", 10, "diskTotalGb", 100, "load1", 0.5,
                        "containers", List.of(), "dockerVersion", "24.0.7", "agentVersion", "0.1.0"), 0L);
            }
        }

        void sendRawStats(Map<String, Object> payload, long seq) {
            WebSocket conn = activeConn;
            if (conn != null && conn.isOpen()) {
                conn.send(NodeProtocol.encode(new NodeProtocol.Envelope(1, "evt", "node.stats", null,
                        seq, System.currentTimeMillis(), payload)));
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // no-op
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // no-op
        }

        @Override
        public void onStart() {
            // 端口绑定完成
        }

        WebSocket lastConn() {
            return activeConn;
        }
    }
}
