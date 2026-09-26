package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例事件同步 / 上线回读 / 节点离线 / 生命周期状态写回归：
 * 节点来源校验、CAS 原子更新（防旧读覆盖、防删后复活）、回读去重、上传实例绑定与 abort。
 */
class McpanelInstanceStateSyncTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final PortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());

    @BeforeEach
    void clean() {
        documents.clear();
    }

    /** 带调用记录的网关：记录 method → payload。 */
    private McpanelInstanceAppService service(RecordingGateway gateway) {
        return new McpanelInstanceAppService(instances, nodes, ports, gateway,
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey, McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty), null);
    }

    /** 记录 (method, payload) 并按脚本应答。 */
    static final class RecordingGateway implements McpanelInstanceAppService.NodeCallGateway {

        final List<String> methods = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();
        final java.util.Queue<java.util.function.Function<String, Map<String, Object>>> script =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        /** 每次调用前触发的钩子（模拟并发事件插入 RPC 执行窗口）。 */
        final java.util.Queue<Runnable> onCall = new java.util.concurrent.ConcurrentLinkedQueue<>();

        @Override
        public CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                           Map<String, Object> payload) {
            return call(nodeId, method, payload, 30_000L);
        }

        @Override
        public CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                           Map<String, Object> payload, long timeoutMs) {
            methods.add(method);
            payloads.add(payload);
            Runnable hook = onCall.poll();
            if (hook != null) {
                hook.run();
            }
            java.util.function.Function<String, Map<String, Object>> answer = script.poll();
            if (answer == null) {
                return CompletableFuture.completedFuture(Map.of());
            }
            try {
                return CompletableFuture.completedFuture(answer.apply(method));
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        Map<String, Object> payloadOf(String method) {
            for (int i = methods.size() - 1; i >= 0; i--) {
                if (method.equals(methods.get(i))) {
                    return payloads.get(i);
                }
            }
            throw new AssertionError("未找到调用：" + method);
        }
    }

    private void onlineNode(String nodeId) {
        nodes.save(McpanelNode.create(nodeId, "节点" + nodeId, "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L));
    }

    private McpanelInstance persisted(String id, String nodeId) {
        McpanelInstance spec = McpanelInstance.create(id, nodeId, "实例" + id, "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of("keep", "yes"), null, "", 1L);
        instances.save(spec.withBinding(null, 1L));
        return instances.findById(id).orElseThrow();
    }

    // ---------- 节点事件 ----------

    @Test
    void eventAppliesStateFromOwningNode() {
        persisted("inst-1", "node-1");
        McpanelInstanceAppService service = service(new RecordingGateway());
        service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-1", "state", "exited",
                "lastExitCode", 137));
        McpanelInstance stored = instances.findById("inst-1").orElseThrow();
        assertEquals("exited", stored.state());
        assertEquals(137, stored.lastExitCode());
        assertEquals("yes", stored.config().get("keep"));
    }

    @Test
    void eventFromForeignNodeIsRejected() {
        persisted("inst-2", "node-1");
        McpanelInstanceAppService service = service(new RecordingGateway());
        // 事件声称来自 node-2，但实例属于 node-1：必须拒绝（不跨节点改写）。
        service.onNodeInstanceEvent("node-2", Map.of("instanceId", "inst-2", "state", "exited"));
        assertEquals("installing", instances.findById("inst-2").orElseThrow().state());
        // 载荷自带 nodeId 且与来源不一致：同样拒绝。
        service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-2", "state", "exited",
                "nodeId", "node-9"));
        assertEquals("installing", instances.findById("inst-2").orElseThrow().state());
    }

    @Test
    void eventForDeletedInstanceDoesNotResurrect() {
        persisted("inst-3", "node-1");
        instances.delete("inst-3");
        McpanelInstanceAppService service = service(new RecordingGateway());
        service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-3", "state", "running"));
        assertTrue(instances.findById("inst-3").isEmpty());
    }

    @Test
    void eventKeepsExitCodeWhenPayloadLacksIt() {
        persisted("inst-4", "node-1");
        instances.save(instances.findById("inst-4").orElseThrow()
                .withState("exited", 3, 10L));
        McpanelInstanceAppService service = service(new RecordingGateway());
        service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-4", "state", "running"));
        McpanelInstance stored = instances.findById("inst-4").orElseThrow();
        assertEquals("running", stored.state());
        // 事件未携带 lastExitCode：不清掉既有退出码。
        assertEquals(3, stored.lastExitCode());
    }

    // ---------- 上线回读 ----------

    @Test
    void syncUpdatesOnlyOwnNodeInstancesAndDedupes() {
        persisted("inst-5", "node-1");
        persisted("inst-6", "node-2");
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> Map.of("records", List.of(
                Map.of("instanceId", "inst-5", "state", "running"),
                // 节点分页异常导致同一实例重复出现：去重，不重复写
                Map.of("instanceId", "inst-5", "state", "running"))));
        gateway.script.add(method -> Map.of("records", List.of()));
        McpanelInstanceAppService service = service(gateway);
        int updated = service.syncStatesFromNode("node-1");
        assertEquals(1, updated);
        assertEquals("running", instances.findById("inst-5").orElseThrow().state());
        // 他节点实例不在 node-1 的回读数据里自然不受影响；本测试同时校验不越权处理。
        assertEquals("installing", instances.findById("inst-6").orElseThrow().state());
    }

    @Test
    void syncSkipsUnchangedStateAndDeletedInstances() {
        persisted("inst-7", "node-1");
        instances.save(instances.findById("inst-7").orElseThrow().withState("running", null, 5L));
        instances.delete("inst-7");
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> Map.of("records", List.of(
                Map.of("instanceId", "inst-7", "state", "running"))));
        gateway.script.add(method -> Map.of("records", List.of()));
        McpanelInstanceAppService service = service(gateway);
        assertEquals(0, service.syncStatesFromNode("node-1"));
        assertTrue(instances.findById("inst-7").isEmpty());
    }

    // ---------- 节点离线 ----------

    @Test
    void offlineMarksOnlyRunningInstancesAsUnknown() {
        persisted("inst-8", "node-1");
        instances.save(instances.findById("inst-8").orElseThrow().withState("running", 2, 5L));
        persisted("inst-9", "node-1");
        instances.save(instances.findById("inst-9").orElseThrow().withState("exited", 0, 5L));
        McpanelInstanceAppService service = service(new RecordingGateway());
        service.onNodeOffline("node-1");
        McpanelInstance running = instances.findById("inst-8").orElseThrow();
        assertEquals("unknown", running.state());
        assertEquals(2, running.lastExitCode());
        assertEquals("exited", instances.findById("inst-9").orElseThrow().state());
    }

    // ---------- action 的 CAS 状态写 ----------

    @Test
    void actionPreservesConcurrentWritesInsteadOfStaleOverwrite() {
        onlineNode("node-1");
        persisted("inst-10", "node-1");
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> "instance.start".equals(method)
                ? Map.of("state", "running") : Map.of());
        McpanelInstanceAppService service = service(gateway);
        // 模拟 start RPC 执行窗口内的并发写（绑定变更）：CAS 状态写不得用旧读覆盖它。
        gateway.onCall.add(() -> {
            instances.save(instances.findById("inst-10").orElseThrow().withBinding("srv-1", 50L));
        });
        Map<String, Object> result = service.action("user:1", "user:1", "inst-10", "start", null);
        assertEquals("running", result.get("state"));
        McpanelInstance stored = instances.findById("inst-10").orElseThrow();
        assertEquals("running", stored.state());
        // 旧实现整档 save 会把并发绑定写回滚；CAS 后必须保留。
        assertEquals("srv-1", stored.mcServerId());
    }

    @Test
    void stopNotRunningMapsToExitedWithoutError() {
        onlineNode("node-1");
        persisted("inst-11", "node-1");
        instances.save(instances.findById("inst-11").orElseThrow().withState("running", null, 5L));
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> {
            throw new NodeCallException("docker.conflict", "容器 is not running");
        });
        McpanelInstanceAppService service = service(gateway);
        Map<String, Object> result = service.action("user:1", "user:1", "inst-11", "stop", null);
        assertEquals("exited", result.get("state"));
        assertEquals("exited", instances.findById("inst-11").orElseThrow().state());
    }

    // ---------- update 回滚保留最新运行时 ----------

    @Test
    void updateFailureRollsBackSpecButKeepsRuntimeState() {
        onlineNode("node-1");
        persisted("inst-12", "node-1");
        instances.save(instances.findById("inst-12").orElseThrow().withState("created", null, 5L));
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> {
            throw new NodeCallException("node.offline", "节点未在线");
        });
        McpanelInstanceAppService service = service(gateway);
        McpanelInstance spec = McpanelInstance.create("inst-12", "node-1", "新名字", "paper",
                "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "x.jar"), Map.of(),
                2048, 2000, 4096, List.of(), Map.of(), null, "", 1L);
        assertThrows(McpanelBusinessException.class,
                () -> service.update("user:1", "user:1", "inst-12", spec));
        McpanelInstance stored = instances.findById("inst-12").orElseThrow();
        // 规格回滚：名字回到旧值
        assertEquals("实例inst-12", stored.name());
        // 运行时不回滚：state 保持节点事件/操作写入的最新值
        assertEquals("created", stored.state());
    }

    /**
     * 配置 CAS 精确回归：文档在「读取之后、CAS 之前」被删（另一 agent 并发删除），
     * update 必须以 409 instance.conflict 失败，绝不整档 save 复活旧快照。
     */
    @Test
    void updateCasConflictReturns409WhenRecordVanishes() {
        onlineNode("node-1");
        persisted("inst-16", "node-1");
        instances.save(instances.findById("inst-16").orElseThrow().withState("created", null, 5L));
        // 装饰仓储：进入 CAS（mutateState）前删除文档，精确模拟「读-删竞态」。
        McpanelInstanceRepository vanishing = new McpanelInstanceRepository() {
            @Override
            public void save(McpanelInstance instance) {
                instances.save(instance);
            }

            @Override
            public java.util.Optional<McpanelInstance> findById(String id) {
                return instances.findById(id);
            }

            @Override
            public List<McpanelInstance> findAll() {
                return instances.findAll();
            }

            @Override
            public void delete(String id) {
                instances.delete(id);
            }

            @Override
            public boolean mutateState(String id, java.util.function.UnaryOperator<McpanelInstance> mutator) {
                instances.delete(id);
                return instances.mutateState(id, mutator);
            }

            @Override
            public boolean mutate(String id, java.util.function.UnaryOperator<McpanelInstance> mutator) {
                instances.delete(id);
                return instances.mutate(id, mutator);
            }
        };
        RecordingGateway gateway = new RecordingGateway();
        McpanelInstanceAppService service = new McpanelInstanceAppService(vanishing, nodes, ports, gateway,
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey, McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty), null);
        McpanelInstance spec = McpanelInstance.create("inst-16", "node-1", "新名字", "paper",
                "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "x.jar"), Map.of(),
                2048, 2000, 4096, List.of(), Map.of(), null, "", 1L);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.update("user:1", "user:1", "inst-16", spec));
        assertEquals("instance.conflict", error.code());
        assertEquals(409, error.httpStatus());
        // 记录不被旧快照复活
        assertTrue(instances.findById("inst-16").isEmpty());
    }

    /**
     * 配置 CAS 精确回归：instance.update RPC 执行窗口内到达的状态事件（已由事件通道
     * 落库）不被 update 成功路径的旧读覆盖——节点回执缺 state 时保留事件写入的最新状态。
     */
    @Test
    void updatePreservesRuntimeStateWrittenDuringRpc() {
        onlineNode("node-1");
        persisted("inst-17", "node-1");
        instances.save(instances.findById("inst-17").orElseThrow().withState("created", null, 5L));
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> Map.of());
        // RPC 窗口内：实例已 exits（例如崩溃）——事件通道经 CAS 落库
        gateway.onCall.add(() -> instances.mutateState("inst-17",
                current -> current.withState("exited", 1, 999L)));
        McpanelInstanceAppService service = service(gateway);
        McpanelInstance spec = McpanelInstance.create("inst-17", "node-1", "新名字", "paper",
                "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "x.jar"), Map.of(),
                2048, 2000, 4096, List.of(), Map.of(), null, "", 1L);
        Map<String, Object> result = service.update("user:1", "user:1", "inst-17", spec);
        // 规格生效
        assertEquals("新名字", result.get("name"));
        // 节点回执无 state：保留 RPC 窗口内事件写入的运行时，而非 forward 前的旧读
        assertEquals("exited", instances.findById("inst-17").orElseThrow().state());
        assertEquals(1, instances.findById("inst-17").orElseThrow().lastExitCode());
    }

    // ---------- 上线回读（过期/越权隔离） ----------

    @Test
    void syncSkipsForeignNodeInstanceListed() {
        // node-2 的实例出现在 node-1 的 instance.list 回读里（过期/伪造会话数据）：拒绝应用。
        persisted("inst-18", "node-2");
        instances.save(instances.findById("inst-18").orElseThrow().withState("created", null, 5L));
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> Map.of("records", List.of(
                Map.of("instanceId", "inst-18", "state", "running"))));
        gateway.script.add(method -> Map.of("records", List.of()));
        McpanelInstanceAppService service = service(gateway);
        assertEquals(0, service.syncStatesFromNode("node-1"));
        assertEquals("created", instances.findById("inst-18").orElseThrow().state());
    }

    @Test
    void syncOverwritesStalePanelStateWithNodeLiveState() {
        // 过期回读正向语义：面板存量过期（如重启残留 running），节点实时 exited 覆盖之。
        persisted("inst-19", "node-1");
        instances.save(instances.findById("inst-19").orElseThrow().withState("running", null, 5L));
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> Map.of("records", List.of(
                Map.of("instanceId", "inst-19", "state", "exited"))));
        gateway.script.add(method -> Map.of("records", List.of()));
        McpanelInstanceAppService service = service(gateway);
        assertEquals(1, service.syncStatesFromNode("node-1"));
        assertEquals("exited", instances.findById("inst-19").orElseThrow().state());
        // 配置等非 runtime 字段保留
        assertEquals("yes", instances.findById("inst-19").orElseThrow().config().get("keep"));
    }

    // ---------- 上传协议绑定与 abort ----------

    @Test
    void uploadCarriesInstanceBindingOnEveryStepAndAbortsOnFailure() {
        onlineNode("node-1");
        persisted("inst-13", "node-1");
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> "file.upload.begin".equals(method)
                ? Map.of("uploadId", "u-1") : Map.of());
        // 第二个 chunk 失败：触发 best-effort abort 并保留原始异常。
        gateway.script.add(method -> {
            throw new NodeCallException("file.chunk", "分块校验失败");
        });
        McpanelInstanceAppService service = service(gateway);
        byte[] data = new byte[200 * 1024];
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.upload("user:1", "inst-13", "world.zip", data,
                        "aa".repeat(32)));
        assertEquals("file.chunk", error.code());
        // begin/chunk/commit-less：每个载荷都绑定 instanceId
        Map<String, Object> beginPayload = gateway.payloadOf("file.upload.begin");
        assertEquals("inst-13", beginPayload.get("instanceId"));
        Map<String, Object> chunkPayload = gateway.payloadOf("file.upload.chunk");
        assertEquals("inst-13", chunkPayload.get("instanceId"));
        assertEquals("u-1", chunkPayload.get("uploadId"));
        // abort 被调用且带 uploadId + instanceId
        Map<String, Object> abortPayload = gateway.payloadOf("file.upload.abort");
        assertEquals("u-1", abortPayload.get("uploadId"));
        assertEquals("inst-13", abortPayload.get("instanceId"));
    }

    @Test
    void uploadCommitCarriesInstanceBinding() {
        onlineNode("node-1");
        persisted("inst-14", "node-1");
        RecordingGateway gateway = new RecordingGateway();
        gateway.script.add(method -> "file.upload.begin".equals(method)
                ? Map.of("uploadId", "u-2") : Map.of());
        McpanelInstanceAppService service = service(gateway);
        service.upload("user:1", "inst-14", "a.txt", new byte[10], "bb".repeat(32));
        Map<String, Object> commitPayload = gateway.payloadOf("file.upload.commit");
        assertEquals("inst-14", commitPayload.get("instanceId"));
        assertEquals("u-2", commitPayload.get("uploadId"));
    }

    @Test
    void uploadRejectsMissingUploadId() {
        onlineNode("node-1");
        persisted("inst-15", "node-1");
        RecordingGateway gateway = new RecordingGateway();
        McpanelInstanceAppService service = service(gateway);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.upload("user:1", "inst-15", "a.txt", new byte[10], "cc".repeat(32)));
        assertEquals("upload.begin", error.code());
    }

    // ---------- stats 快照纠偏（docker events 丢失的补偿路径） ----------

    @Test
    void statsSnapshotReconcilesStaleExitedToRunning() {
        // 节点 events 断流期间容器已启动：DB 停在 exited，stats 上报 running → 纠偏。
        onlineNode("node-1");
        persisted("inst-20", "node-1");
        instances.save(instances.findById("inst-20").orElseThrow().withState("exited", 0, 5L));
        McpanelInstanceAppService service = service(new RecordingGateway());
        int updated = service.onNodeStatsSnapshot("node-1", Map.of(
                "containers", List.of(
                        Map.of("instanceId", "inst-20", "state", "running"),
                        Map.of("instanceId", "ghost", "state", "running"))));
        assertEquals(1, updated);
        assertEquals("running", instances.findById("inst-20").orElseThrow().state());
        // 快照与 DB 一致时幂等：不产生新写。
        assertEquals(0, service.onNodeStatsSnapshot("node-1", Map.of(
                "containers", List.of(Map.of("instanceId", "inst-20", "state", "running")))));
    }

    @Test
    void statsSnapshotNormalizesStoppedAndSkipsUnknown() {
        onlineNode("node-1");
        persisted("inst-21", "node-1");
        instances.save(instances.findById("inst-21").orElseThrow().withState("running", null, 5L));
        McpanelInstanceAppService service = service(new RecordingGateway());
        // stopped 统一记 exited；unknown 不作为纠偏依据。
        assertEquals(1, service.onNodeStatsSnapshot("node-1", Map.of(
                "containers", List.of(Map.of("instanceId", "inst-21", "state", "stopped")))));
        assertEquals("exited", instances.findById("inst-21").orElseThrow().state());
        assertEquals(0, service.onNodeStatsSnapshot("node-1", Map.of(
                "containers", List.of(Map.of("instanceId", "inst-21", "state", "unknown")))));
        assertEquals("exited", instances.findById("inst-21").orElseThrow().state());
    }

    @Test
    void statsSnapshotFromForeignNodeIsRejectedAndNoInferenceForMissing() {
        onlineNode("node-1");
        onlineNode("node-2");
        persisted("inst-22", "node-1");
        instances.save(instances.findById("inst-22").orElseThrow().withState("exited", 0, 5L));
        McpanelInstanceAppService service = service(new RecordingGateway());
        // 快照声称来自 node-2，实例属于 node-1：拒绝。
        assertEquals(0, service.onNodeStatsSnapshot("node-2", Map.of(
                "containers", List.of(Map.of("instanceId", "inst-22", "state", "running")))));
        assertEquals("exited", instances.findById("inst-22").orElseThrow().state());
        // 不在快照中的实例不做推断（停机侧由事件/回读负责）。
        assertEquals(0, service.onNodeStatsSnapshot("node-1", Map.of("containers", List.of())));
        assertEquals("exited", instances.findById("inst-22").orElseThrow().state());
    }

    @Test
    void exitEventTriggersEventTaskAutoRestartWhileRunningEventDoesNot() throws Exception {
        onlineNode("node-1");
        RecordingGateway gateway = new RecordingGateway();
        McpanelInstanceAppService service = service(gateway);
        online.yudream.base.plugin.mcpanel.application.service.InstanceEventTaskService eventTasks =
                new online.yudream.base.plugin.mcpanel.application.service.InstanceEventTaskService(
                        instances,
                        (nodeId, method, payload, timeoutMs) -> gateway.call(nodeId, method, payload, timeoutMs),
                        (actor, action, targetType, targetId, detail, tenantId) -> {
                        });
        try {
            service.attachEventTasks(eventTasks);
            persisted("inst-23", "node-1");
            instances.save(instances.findById("inst-23").orElseThrow()
                    .withState("running", null, 5L)
                    .withEventTask(true, false, 5L));

            service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-23", "state", "exited"));
            long deadline = System.currentTimeMillis() + 5000;
            while (countOf(gateway, "instance.start") == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, countOf(gateway, "instance.start"), "意外退出事件应触发事件任务自动重启");

            // 运行中事件不触发；无变化事件（accepted=false）同样不触发。
            int before = countOf(gateway, "instance.start");
            service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-23", "state", "running"));
            service.onNodeInstanceEvent("node-1", Map.of("instanceId", "inst-23", "state", "running"));
            Thread.sleep(200);
            assertEquals(before, countOf(gateway, "instance.start"), "非退出事件不得触发事件任务");
        } finally {
            eventTasks.close();
        }
    }

    private static int countOf(RecordingGateway gateway, String method) {
        return (int) gateway.methods.stream().filter(method::equals).count();
    }
}
