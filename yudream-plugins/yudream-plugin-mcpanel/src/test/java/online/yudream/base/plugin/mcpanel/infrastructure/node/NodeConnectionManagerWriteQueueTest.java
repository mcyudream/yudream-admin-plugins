package online.yudream.base.plugin.mcpanel.infrastructure.node;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站写帧队列与 pending 生命周期的连接级回归（受控 WebSocket 替身，非 mock 总线）：
 * - 旧缺陷重现：JDK sendText 返回的写 future 异步失败曾被丢弃 → 调用挂到超时、
 *   前端超时后重试秒开；修复后必须立即异常完成并关闭连接；
 * - 断线/移除：全部 pending 立即以 node.offline 完成（不再静默 clear 滞留到超时）；
 * - 调用方超时后，尚未发出的排队帧必须被丢弃（超时报错后变更不得再发出）；
 * - 出站帧超 256KB：本地 proto.badFrame 失败，不写 socket、不断连接；
 * - hello 写失败：立即按握手失败关闭。
 */
class NodeConnectionManagerWriteQueueTest {

    private static final String NODE = "node-wq";

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final InMemorySecretStore secretStore = new InMemorySecretStore();
    private final McpanelNodeRepository repository =
            new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final NodeSecrets secrets = new NodeSecrets(secretStore);
    private final NodeEventBus eventBus = new NodeEventBus();

    private final StubWebSocket socket = new StubWebSocket();
    private final AtomicReference<java.net.http.WebSocket.Listener> listenerRef = new AtomicReference<>();
    private NodeConnectionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    /** 拨号注入替身 socket，走完整 dial → onOpen → hello res → ONLINE 流程。 */
    private void goOnline() throws Exception {
        secretStore.put(NodeSecrets.key(NODE), "secret-wq".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        McpanelNode node = McpanelNode.create(NODE, NODE, "ws://127.0.0.1:1", "pkix", null, true, null, true,
                System.currentTimeMillis());
        node = node.withReported("0.1.0", NODE + "-host", null, System.currentTimeMillis());
        repository.save(node);
        manager = new NodeConnectionManager(repository, secrets, eventBus, 15_000L, 5_000L, 30L,
                (nodeId, cfg, secret, listener) -> {
                    listenerRef.set(listener);
                    return CompletableFuture.completedFuture(socket);
                });
        manager.start();
        manager.syncNode(node);
        await("dialed", () -> listenerRef.get() != null);
        // 模拟内核接受连接：onOpen → hello 经写队列异步发出。
        listenerRef.get().onOpen(socket);
        await("hello sent", () -> socket.sendTextCount() >= 1);
        Optional<NodeProtocol.Envelope> hello = NodeProtocol.decode(socket.sentFrames.get(0));
        assertTrue(hello.isPresent() && NodeProtocol.M_NODE_HELLO.equals(hello.get().m()));
        Map<String, Object> payload = Map.of(
                "nodeId", NODE,
                "sessionId", NodeProtocol.newUlid(),
                "hostname", NODE + "-host",
                "agentVersion", "0.1.0",
                "caps", List.of("node.hello", "node.stats", "file.read", "file.list", "file.write", "instance.stop"));
        String res = NodeProtocol.encode(NodeProtocol.Envelope.response(hello.get(), payload));
        listenerRef.get().onText(socket, res, true);
        await("node online", () -> !manager.caps(NODE).isEmpty());
        socket.sentFrames.clear();
    }

    @Test
    void asyncSendFailureFailsPendingCallImmediatelyAndCloses() throws Exception {
        goOnline();
        socket.autoCompleteWrites = false;
        CompletableFuture<Map<String, Object>> call =
                manager.call(NODE, "file.read", Map.of("instanceId", "i-1", "path", "server.properties"), 8_000L);
        await("request handed to wire", () -> socket.sendTextCount() >= 1);
        // 旧缺陷：此异步失败被丢弃，调用挂满 8s 超时；修复后必须立刻失败。
        socket.failPendingWrites(new java.io.IOException("broken pipe"));
        await("call failed fast", call::isDone);
        assertTrue(call.isCompletedExceptionally());
        NodeCallException error = assertInstanceOf(NodeCallException.class, causeOf(call));
        assertEquals("node.offline", error.code());
        assertFalse(manager.runtime(NODE).connected(), "写失败后连接必须关闭");
    }

    private static Throwable causeOf(CompletableFuture<Map<String, Object>> future) {
        try {
            future.join();
        } catch (java.util.concurrent.CompletionException error) {
            return error.getCause() == null ? error : error.getCause();
        }
        return null;
    }

    @Test
    void disconnectCompletesAndClearsAllPendingImmediately() throws Exception {
        goOnline();
        socket.autoCompleteWrites = false;
        CompletableFuture<Map<String, Object>> first = manager.call(NODE, "file.read", Map.of(), 30_000L);
        CompletableFuture<Map<String, Object>> second = manager.call(NODE, "file.list", Map.of(), 30_000L);
        // 头帧已交给 wire，第二帧按保序语义滞留队列（写完成前不发后续写）。
        await("head handed to wire", () -> socket.sendTextCount() >= 1);
        manager.removeNode(NODE);
        await("first pending failed", first::isDone);
        await("second pending failed", second::isDone);
        assertTrue(first.isCompletedExceptionally() && second.isCompletedExceptionally());
        NodeCallException cause = assertInstanceOf(NodeCallException.class, causeOf(first));
        assertEquals("node.offline", cause.code(), "断线必须显式失败 pending，不得静默丢弃");
    }

    @Test
    void callerTimeoutDiscardsUndeliveredQueuedFrame() throws Exception {
        goOnline();
        socket.autoCompleteWrites = false;
        // 头帧卡在内核写缓冲（写 future 永不完成）→ 队列阻塞。
        CompletableFuture<Map<String, Object>> head =
                manager.call(NODE, "file.read", Map.of("path", "head.txt"), 30_000L);
        await("head handed to wire", () -> socket.sendTextCount() >= 1);
        // 第二个变更调用排在卡死的头帧之后，很快超时。
        CompletableFuture<Map<String, Object>> mutation =
                manager.call(NODE, "instance.stop", Map.of("instanceId", "i-1"), 250L);
        await("mutation timed out", mutation::isDone);
        // 等待超时回调链（摘除登记 → discard 队列帧）传播完成：回调在同一完成线程
        // 顺序执行，此处仅覆盖「观察者读到 isDone 与 discard 执行之间」的微小窗口。
        Thread.sleep(200);
        assertEquals(1, socket.sendTextCount(), "超时后未发出的排队帧必须已从队列摘除");
        // 头帧字节此时才真正写出：不得把被丢弃的变更帧透传给 wire。
        socket.completeOldestPendingWrite();
        await("no further write after caller timeout", () -> socket.sendTextCount() == 1);
        assertFalse(head.isCompletedExceptionally(), "头帧写完成不得连带失败");
    }

    @Test
    void outboundFrameOver256KBRejectedLocallyWithoutWriting() throws Exception {
        goOnline();
        int writesBefore = socket.sendTextCount();
        String huge = "x".repeat(300 * 1024);
        CompletableFuture<Map<String, Object>> call = manager.call(NODE, "file.write",
                Map.of("instanceId", "i-1", "path", "big.bin", "content", huge), 5_000L);
        await("rejected locally", call::isDone);
        assertTrue(call.isCompletedExceptionally());
        NodeCallException cause = assertInstanceOf(NodeCallException.class, causeOf(call));
        assertEquals("proto.badFrame", cause.code(), "出站超限必须本地失败");
        assertEquals(writesBefore, socket.sendTextCount(), "超限帧不得写 socket");
        assertTrue(manager.runtime(NODE).online(), "本地拒绝不影响连接");
    }

    @Test
    void helloWriteFailureClosesConnectionImmediately() throws Exception {
        secretStore.put(NodeSecrets.key(NODE), "secret-wq".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        McpanelNode node = McpanelNode.create(NODE, NODE, "ws://127.0.0.1:1", "pkix", null, true, null, true,
                System.currentTimeMillis());
        node = node.withReported("0.1.0", NODE + "-host", null, System.currentTimeMillis());
        repository.save(node);
        socket.autoCompleteWrites = false;
        manager = new NodeConnectionManager(repository, secrets, eventBus, 15_000L, 5_000L, 30L,
                (nodeId, cfg, secret, listener) -> {
                    listenerRef.set(listener);
                    return CompletableFuture.completedFuture(socket);
                });
        manager.start();
        manager.syncNode(node);
        await("dialed", () -> listenerRef.get() != null);
        listenerRef.get().onOpen(socket);
        await("hello handed to wire", () -> socket.sendTextCount() >= 1);
        // 旧实现：sendText 不抛同步异常 → hello 挂满 30s 才握手失败。
        socket.failPendingWrites(new java.io.IOException("tcp reset"));
        await("closed on hello write failure", () -> !manager.runtime(NODE).connected());
        assertTrue(manager.caps(NODE).isEmpty(), "握手失败不得上线");
    }
}
