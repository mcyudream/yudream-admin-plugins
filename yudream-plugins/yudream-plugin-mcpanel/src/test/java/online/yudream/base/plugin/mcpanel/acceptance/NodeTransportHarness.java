package online.yudream.base.plugin.mcpanel.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.service.NodeEndpointPolicy;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeConnectionManager;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;

import java.util.List;

/**
 * 跨语言验收入口（真实 WSS 传输，非 mock）：以面板侧真实拨号栈连接
 * 真实节点守护进程（mcpanel-node / Go 或任意协议实现）。
 *
 * <p>用法（javac/java 直接运行，无 JUnit 依赖；classpath =
 * target/classes + target/test-classes + SPI/jackson 依赖）：
 * <pre>
 * java online.yudream.base.plugin.mcpanel.acceptance.NodeTransportHarness \
 *   wss://127.0.0.1:9701 &lt;pinSha256|pkix&gt; &lt;nodeId&gt; &lt;nodeSecret&gt; [waitSeconds]
 * </pre>
 * 行为：注册节点记录（loopback + localDevelopment，pinned 时按指纹钉扎）→
 * 注入 nodeSecret → 启动真实拨号/重连栈 → 打印 node.state/node.stats 事件与
 * 最终状态 JSON → 退出。全程不输出任何凭据。
 */
public final class NodeTransportHarness {

    private NodeTransportHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: NodeTransportHarness <wss://host:port> <pinSha256|pkix> <nodeId> <nodeSecret> [waitSeconds]");
            System.exit(2);
        }
        String endpoint = args[0];
        String pinOrMode = args[1];
        String nodeId = args[2];
        String nodeSecret = args[3];
        long waitSeconds = args.length > 4 ? Long.parseLong(args[4]) : 15L;

        boolean pinned = !"pkix".equalsIgnoreCase(pinOrMode);
        ObjectMapper mapper = McpanelJson.mapper();
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        InMemorySecretStore secretStore = new InMemorySecretStore();
        McpanelNodeRepository repository = new DocumentNodeRepository(documents, mapper);
        NodeSecrets secrets = new NodeSecrets(secretStore);
        NodeEventBus eventBus = new NodeEventBus();

        // 模拟已 bootstrap 的节点：凭据已在 SecretStore。
        secretStore.put(NodeSecrets.key(nodeId), nodeSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        McpanelNode node = McpanelNode.create(nodeId, "acceptance-node", endpoint,
                pinned ? NodeEndpointPolicy.TLS_PINNED : NodeEndpointPolicy.TLS_PKIX,
                pinned ? pinOrMode.toLowerCase(java.util.Locale.ROOT) : null,
                true, "transport harness", true, now)
                // 生产 Conn 的拨号门禁在 enrolled 上：harness 模拟已完成注册的节点。
                .withReported("harness", "acceptance-node", null, now);
        repository.save(node);

        eventBus.subscribe(new online.yudream.base.plugin.spi.http.PluginSseStream.Subscriber() {
            @Override
            public void send(String event, Object payload) {
                try {
                    System.out.println("[sse] " + event + " " + mapper.writeValueAsString(payload));
                } catch (Exception ignored) {
                    System.out.println("[sse] " + event);
                }
            }

            @Override
            public void complete() {
                System.out.println("[sse] complete");
            }

            @Override
            public void error(Throwable error) {
                System.out.println("[sse] error: " + error.getMessage());
            }
        });

        NodeConnectionManager manager = new NodeConnectionManager(repository, secrets, eventBus,
                15_000L, 5_000L, 500L);
        manager.start();
        manager.syncNode(node);
        McpanelNode latest;
        NodeConnectionManager.NodeRuntime runtime;
        try {
            Thread.sleep(waitSeconds * 1000L);
            // 先采样运行时状态，再在 finally 中释放资源。
            latest = repository.findById(nodeId).orElseThrow();
            runtime = manager.runtime(nodeId);
            System.out.println("[result] status=" + latest.status()
                    + " connected=" + runtime.connected()
                    + " online=" + runtime.online()
                    + " agentVersion=" + latest.agentVersion()
                    + " sessionId=" + latest.sessionId()
                    + " caps=" + latest.caps()
                    + " dockerVersion=" + latest.dockerVersion()
                    + " lastSeenAt=" + latest.lastSeenAtMs()
                    + (latest.lastStats() != null ? " statsReportedAt=" + latest.lastStats().reportedAtMs() : " stats=none"));
        } finally {
            // 验收资源收尾：连接调度与 SSE 心跳/订阅一并释放。
            manager.close();
            eventBus.completeAll();
        }
        if (!runtime.online()) {
            System.exit(1);
        }
    }
}
