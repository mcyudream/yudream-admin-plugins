package online.yudream.base.plugin.mcpanel.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeConnectionManager;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 跨语言业务闭环验收入口（真实面板应用服务 → 真实 Go 节点 → 真实 Docker）：
 * 建实例（含端口分配）→ 启动 → 控制台输出 → 文件写入/读取 → 停止 → 删除。
 * 运行方式同 NodeTransportHarness：classpath = classes + test-classes + SPI/jackson。
 */
public final class InstanceBusinessHarness {

    private InstanceBusinessHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: InstanceBusinessHarness <wss://host:port> <pinSha256> <nodeId> <nodeSecret>");
            System.exit(2);
        }
        String endpoint = stripPath(args[0]);
        String pin = args[1];
        String nodeId = args[2];
        String nodeSecret = args[3];
        ObjectMapper mapper = McpanelJson.mapper();
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        InMemorySecretStore secretStore = new InMemorySecretStore();

        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, mapper);
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, mapper);
        PortAllocationRepository ports = new DocumentPortAllocationRepository(documents, mapper);
        secretStore.put(NodeSecretsKey.of(nodeId), nodeSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        McpanelNode node = McpanelNode.create(nodeId, "验收节点", endpoint, "pinned", pin.toLowerCase(), true, "harness", true, System.currentTimeMillis());
        nodes.save(node.withReported("0.2.0", "harness", pin.toLowerCase(), System.currentTimeMillis()));

        NodeEventBus bus = new NodeEventBus();
        online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets secrets =
                new online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets(secretStore);
        NodeConnectionManager manager = new NodeConnectionManager(nodes, secrets, bus);
        manager.start();
        bus.startHeartbeat();
        manager.syncNode(nodes.findById(nodeId).orElseThrow());

        McpanelInstanceAppService service = new McpanelInstanceAppService(instances, nodes, ports,
                (nodeIdArg, method, payload) -> {
                    CompletableFuture<Map<String, Object>> future = manager.call(nodeIdArg, method, payload);
                    // 直接复用 manager.call 的超时/错误映射。
                    return future;
                },
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey, McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode target) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty),
                null);

        // 1. 等待节点上线（hello + stats）
        waitOnline(manager, nodeId, 30);
        System.out.println("OK node online caps=" + manager.caps(nodeId));

        // 2. 拉镜像（任务）
        Map<String, Object> pull = service.imagePull("user:1", nodeId, "alpine:3.20");
        String taskId = String.valueOf(pull.get("taskId"));
        for (int i = 0; i < 120; i++) {
            Map<String, Object> state = service.task("user:1", nodeId, taskId);
            if ("done".equals(state.get("state"))) {
                System.out.println("OK image.pull done");
                break;
            }
            if ("failed".equals(state.get("state")) || "canceled".equals(state.get("state"))) {
                throw new IllegalStateException("镜像拉取失败: " + state);
            }
            Thread.sleep(2000);
        }

        // 3. 创建实例（端口分配 + 幂等载荷下发）
        McpanelInstance spec = McpanelInstance.create("harness-inst-001", nodeId, "验收实例", "generic", "",
                "", "alpine:3.20", List.of("sh", "-c", "i=0; while true; do echo tick-$i; i=$((i+1)); sleep 1; done"),
                Map.of(), 128, 100, 64, List.of(), Map.of(), null, "", System.currentTimeMillis());
        Map<String, Object> created = service.create("user:1", "user:1", spec);
        System.out.println("OK instance.create state=" + created.get("state") + " ports=" + created.get("ports"));

        // 4. 启动 → 运行中
        Map<String, Object> started = service.action("user:1", "user:1", "harness-inst-001", "start", null);
        if (!"running".equals(String.valueOf(started.get("state")))) {
            throw new IllegalStateException("启动后状态异常: " + started.get("state"));
        }
        System.out.println("OK instance.start running");

        // 5. 控制台输出
        Thread.sleep(3000);
        Map<String, Object> output = service.output("user:1", "harness-inst-001", null, 50);
        if (!String.valueOf(output.get("text")).contains("tick-")) {
            throw new IllegalStateException("控制台输出缺失: " + output.get("text"));
        }
        System.out.println("OK instance.output.read cursor=" + output.get("nextCursor"));

        // 6. 文件写入/读取/列表
        String content = java.util.Base64.getEncoder().encodeToString("panel-harness".getBytes());
        service.files("user:1", "harness-inst-001", "write",
                Map.of("path", "panel/harness.txt", "content", content, "encoding", "base64"));
        Map<String, Object> readBack = service.files("user:1", "harness-inst-001", "read", Map.of("path", "panel/harness.txt"));
        if (!content.equals(readBack.get("content"))) {
            throw new IllegalStateException("文件回读不一致");
        }
        Map<String, Object> listing = service.files("user:1", "harness-inst-001", "list", Map.of("path", "panel"));
        System.out.println("OK file write/read/list entries=" + listing.get("entries"));

        // 7. 备份
        Map<String, Object> backup = service.backup("user:1", "user:1", "harness-inst-001", "create", Map.of());
        System.out.println("OK backup.create " + backup.get("file"));

        // 8. 停止 → 删除（回收端口）
        service.action("user:1", "user:1", "harness-inst-001", "stop", 10);
        Map<String, Object> deleted = service.delete("user:1", "user:1", "harness-inst-001", false);
        if (!Boolean.TRUE.equals(deleted.get("deleted"))) {
            throw new IllegalStateException("删除返回异常: " + deleted);
        }
        if (ports.countByNode(nodeId) != 0) {
            throw new IllegalStateException("删除后端口未回收");
        }
        System.out.println("OK instance.stop + delete (ports released)");

        bus.completeAll();
        manager.close();
        System.out.println("PANEL BUSINESS SMOKE OK");
        System.exit(0);
    }

    /** endpoint 只允许 authority：剥掉误带的路径（连接脚本可能给完整 /control URL）。 */
    private static String stripPath(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart > 0 ? url.substring(0, pathStart) : url;
    }

    private static void waitOnline(NodeConnectionManager manager, String nodeId, int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (manager.runtime(nodeId).online()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("节点 " + timeoutSec + "s 内未上线");
    }

    /** InMemorySecretStore 键名对齐 NodeSecrets（nodes/{id}/secret）。 */
    private static final class NodeSecretsKey {
        static String of(String nodeId) {
            return "nodes/" + nodeId + "/secret";
        }
    }
}
