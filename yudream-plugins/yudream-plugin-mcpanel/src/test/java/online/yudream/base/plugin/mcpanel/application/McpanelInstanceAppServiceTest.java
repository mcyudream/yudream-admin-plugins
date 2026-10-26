package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.InstancePolicy;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实例应用服务单测：端口分配、幂等创建、失败回收、越权规格拒绝。 */
class McpanelInstanceAppServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final PortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());
    private final AtomicInteger createCalls = new AtomicInteger();
    private final Map<String, Object> createdPayload = new java.util.concurrent.ConcurrentHashMap<>();

    private McpanelInstanceAppService service(java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> callBehavior) {
        return new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> {
                    Map<String, Object> result = callBehavior.apply(method, payload);
                    return CompletableFuture.completedFuture(result == null ? Map.of() : result);
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
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty), null);
    }

    private McpanelNode onlineNode() {
        McpanelNode node = McpanelNode.create("node-1", "节点一", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L);
        nodes.save(node.withReported("0.2.0", "h", "a".repeat(64), 1L));
        return nodes.findById("node-1").orElseThrow();
    }

    private McpanelInstance spec(String id, String kind) {
        return McpanelInstance.create(id, "node-1", "实例" + id, kind, "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L)
                .withBinding(null, 1L);
    }

    @Test
    void createAllocatesTcpPortAndSendsIdempotentPayload() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                createCalls.incrementAndGet();
                createdPayload.putAll(payload);
                return Map.of("instanceId", payload.get("instanceId"), "state", "created");
            }
            return Map.of();
        });
        Map<String, Object> created = service.create("user:1", "user:1", spec("inst-a", "paper"));
        assertEquals("created", created.get("state"));
        assertEquals(1, createCalls.get());
        assertEquals("panel-create-inst-a", createdPayload.get("ik"));
        assertNotNull(created.get("ports"));
        assertTrue(((List<?>) created.get("ports")).size() >= 1);
        assertEquals(1L, ports.countByNode("node-1"));
    }

    @Test
    void createBedrockAllocatesUdpPort() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        Map<String, Object> created = service.create("user:1", "user:1", spec("inst-b", "bedrock"));
        List<?> mappings = (List<?>) created.get("ports");
        assertEquals(1, mappings.size());
        McpanelInstance.PortMapping mapping = (McpanelInstance.PortMapping) mappings.get(0);
        assertEquals("udp", mapping.proto());
    }

    @Test
    void createFailureReleasesPortAndDeletesRecord() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException("node.offline", 502, "节点离线");
        });
        assertThrows(McpanelBusinessException.class,
                () -> service.create("user:1", "user:1", spec("inst-c", "paper")));
        assertTrue(instances.findAll().isEmpty());
        assertEquals(0L, ports.countByNode("node-1"));
    }

    @Test
    void invalidSpecRejectedBeforeNodeCall() {
        McpanelInstance bad = McpanelInstance.create("inst-d", "node-1", "坏规格", "paper", "1.21", "",
                "eclipse-temurin:21-jre", List.of(), Map.of(),
                10, 10, 10, List.of(), Map.of(), null, "", 1L);
        assertThrows(McpanelBusinessException.class, () -> InstancePolicy.validate(bad));
        McpanelInstanceAppService service = service((m, p) -> Map.of());
        assertThrows(McpanelBusinessException.class,
                () -> service.create("user:1", "user:1", bad));
    }

    @Test
    void cpuZeroMeansUnlimitedAndSmallValuesRejected() {
        McpanelInstance unlimited = McpanelInstance.create("inst-e", "node-1", "不限CPU", "paper", "1.21", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 0, 2048, List.of(), Map.of(), null, "", 1L);
        InstancePolicy.validate(unlimited);
        McpanelInstance tooSmall = McpanelInstance.create("inst-f", "node-1", "坏CPU", "paper", "1.21", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 50, 2048, List.of(), Map.of(), null, "", 1L);
        assertThrows(McpanelBusinessException.class, () -> InstancePolicy.validate(tooSmall));
    }

    @Test
    void deleteReleasesPortsAndProxiesNode() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        service.create("user:1", "user:1", spec("inst-e", "paper"));
        assertEquals(1L, ports.countByNode("node-1"));
        Map<String, Object> deleted = service.delete("user:1", "user:1", "inst-e", false);
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(0L, ports.countByNode("node-1"));
        assertTrue(instances.findAll().isEmpty());
    }

    // ---------- 回收站快照与 purge 能力预检 ----------

    @Test
    void deleteWithTrashedResponseSnapshotsToTrashSink() {
        onlineNode();
        List<String> sinkCalls = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.delete".equals(method)) {
                return Map.of("deleted", true, "trashed", true);
            }
            return Map.of("state", "created");
        });
        service.attachTrashSink((instance, actor) -> sinkCalls.add(instance.id() + ":" + actor));
        service.create("user:1", "user:1", spec("inst-t1", "paper"));
        service.delete("user:1", "user:1", "inst-t1", false);
        assertEquals(List.of("inst-t1:user:1"), sinkCalls);
    }

    @Test
    void deleteWithoutTrashedFlagSkipsTrashSink() {
        onlineNode();
        List<String> sinkCalls = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        service.attachTrashSink((instance, actor) -> sinkCalls.add(instance.id()));
        service.create("user:1", "user:1", spec("inst-t2", "paper"));
        // 老节点应答没有 trashed 字段 = 目录原地保留，不产生回收站记录。
        service.delete("user:1", "user:1", "inst-t2", false);
        assertTrue(sinkCalls.isEmpty());
    }

    @Test
    void purgeRequiresNodeCapability() {
        onlineNode();
        // onlineNode 的节点 caps 为空：purge 必须被 409 预检拒绝，且不发出节点调用。
        List<String> methods = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            return Map.of("purged", true);
        });
        service.create("user:1", "user:1", spec("inst-t3", "paper"));
        methods.clear();
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.delete("user:1", "user:1", "inst-t3", true));
        assertEquals("node.capability", error.code());
        assertTrue(methods.isEmpty());
        // 节点上报 instance.purge 能力后 purge 正常下发。
        nodes.save(nodes.findById("node-1").orElseThrow().withRuntime(
                online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus.ONLINE,
                "0.4.0", "h", "sess", List.of("instance.purge"), "27", null, 1L, 1L));
        Map<String, Object> deleted = service.delete("user:1", "user:1", "inst-t3", true);
        assertEquals(Boolean.TRUE, deleted.get("purged"));
        assertEquals(List.of("instance.purge"), methods);
    }

    @Test
    void createOverloadMergesNodePayloadExtras() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                createdPayload.putAll(payload);
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-t4", "paper"), Map.of("fromTrash", "inst-t4-123"));
        assertEquals("inst-t4-123", createdPayload.get("fromTrash"));
        assertEquals("panel-create-inst-t4", createdPayload.get("ik"));
    }

    // ---------- 节点删除级联清理 ----------

    /** 带调用记录的实例服务：记录 (method, ik)，便于断言级联的节点调用。 */
    private McpanelInstanceAppService recordingService(java.util.List<String> calls) {
        return new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> {
                    calls.add(method + ":" + payload.get("ik"));
                    return CompletableFuture.completedFuture(Map.of());
                },
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> calls.add("audit:" + action + ":" + targetId),
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

    @Test
    void cascadeDeleteByNodeCleansOnlyTargetNodeInstances() {
        onlineNode();
        nodes.save(McpanelNode.create("node-2", "节点二", "wss://127.0.0.1:9702", "pinned",
                "b".repeat(64), true, null, true, 1L));
        List<String> calls = new java.util.ArrayList<>();
        McpanelInstanceAppService service = recordingService(calls);
        service.create("user:1", "user:1", spec("inst-c1", "paper"));
        service.create("user:1", "user:1", spec("inst-c2", "vanilla"));
        // node-2 上的实例不得被 node-1 的级联波及
        McpanelInstance other = McpanelInstance.create("inst-other", "node-2", "别节点实例", "paper",
                "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L).withBinding(null, 1L);
        instances.save(other);
        ports.allocate("node-2", 30000, "tcp", "inst-other");

        int cleaned = service.cascadeDeleteByNode("node-1");

        assertEquals(2, cleaned);
        // 目标节点实例记录与端口全部清理，其余节点实例不受影响
        assertEquals(1, instances.findAll().size());
        assertEquals("inst-other", instances.findAll().get(0).id());
        assertEquals(0L, ports.countByNode("node-1"));
        assertEquals(1L, ports.countByNode("node-2"));
        // 每个实例都收到一次保留数据的容器删除调用
        assertTrue(calls.contains("instance.delete:panel-delete-inst-c1-keep"));
        assertTrue(calls.contains("instance.delete:panel-delete-inst-c2-keep"));
        assertTrue(calls.stream().filter(call -> call.startsWith("audit:instance.node-cascade:")).count() == 2);
    }

    @Test
    void cascadeDeleteByNodeStillCleansPanelStateWhenNodeOffline() {
        onlineNode();
        List<String> calls = new java.util.ArrayList<>();
        McpanelInstanceAppService service = new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> CompletableFuture.failedFuture(
                        new online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException(
                                "node.offline", "节点未在线")),
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> calls.add("audit:" + action + ":" + targetId),
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
        // 节点离线 → 容器无从创建/删除：直接落库实例记录与端口占用模拟存量数据
        instances.save(spec("inst-off", "paper"));
        ports.allocate("node-1", 25565, "tcp", "inst-off");

        int cleaned = service.cascadeDeleteByNode("node-1");

        // 节点离线时容器无从删除，但面板侧记录/端口必须清理，不留孤儿实例
        assertEquals(1, cleaned);
        assertTrue(instances.findAll().isEmpty());
        assertEquals(0L, ports.countByNode("node-1"));
        assertTrue(calls.contains("audit:instance.node-cascade.failed:inst-off"));
        assertTrue(calls.contains("audit:instance.node-cascade:inst-off"));
    }

    @Test
    void createPassesLongTimeoutToGateway() {
        onlineNode();
        java.util.List<Long> timeouts = new java.util.ArrayList<>();
        McpanelInstanceAppService service = new McpanelInstanceAppService(instances, nodes, ports,
                new McpanelInstanceAppService.NodeCallGateway() {
                    @Override
                    public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> call(
                            String nodeId, String method, java.util.Map<String, Object> payload) {
                        return CompletableFuture.completedFuture(Map.of("state", "created"));
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> call(
                            String nodeId, String method, java.util.Map<String, Object> payload, long timeoutMs) {
                        timeouts.add(timeoutMs);
                        return CompletableFuture.completedFuture(Map.of("state", "created"));
                    }
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
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty), null);
        service.create("user:1", "user:1", spec("inst-t", "paper"));
        // 创建走 15 分钟长超时（内联拉镜像）；gateway 不得把超时钳回默认 30s
        assertEquals(1, timeouts.size());
        assertEquals(900_000L, timeouts.get(0));
    }

    @Test
    void fileListReturnsEmptyDirectoryWhenNodeHasNoInstanceData() {
        onlineNode();
        instances.save(spec("inst-f", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException(
                    McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                    "instance.notFound：节点上不存在该实例的容器/数据。");
        });

        Map<String, Object> listed = service.files("user:1", "inst-f", "list", Map.of("path", ""));

        // 实例数据尚未就位：列目录按空目录处理，页面展示空态而不是报错
        assertNotNull(listed.get("entries"));
        assertTrue(((List<?>) listed.get("entries")).isEmpty());
        assertEquals(0, ((Number) listed.get("total")).intValue());
    }

    @Test
    void fileReadReturnsNotFoundInsteadOfInstanceConflictWhenNodeHasNoInstanceData() {
        onlineNode();
        instances.save(spec("inst-g", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException(
                    McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                    "instance.notFound：节点上不存在该实例的容器/数据。");
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.files("user:1", "inst-g", "read", Map.of("path", "server.properties")));
        // 文件级缺失按 404 语义上报，不再套用实例级 409 文案误导为「容器/数据被清空」
        assertEquals("file.notFound", error.code());
        assertEquals(404, error.httpStatus());
    }

    @Test
    void outputReturnsEmptyHistoryWhenNodeHasNoInstanceData() {
        onlineNode();
        instances.save(spec("inst-o", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException(
                    McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                    "instance.notFound：节点上不存在该实例的容器/数据。");
        });

        Map<String, Object> history = service.output("user:1", "inst-o", null, 250);

        // 实例尚无容器/数据：控制台历史按空处理，不再把缺失报成 409
        assertEquals("", history.get("text"));
        assertEquals("", history.get("data"));
        assertEquals(Boolean.FALSE, history.get("truncated"));
    }

    @Test
    void outputStillFailsForOtherNodeErrors() {
        onlineNode();
        instances.save(spec("inst-p", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException("node.timeout", 504, "节点响应超时");
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.output("user:1", "inst-p", null, 250));
        assertEquals("node.timeout", error.code());
    }

    @Test
    void backupListReturnsEmptyWhenNodeHasNoInstanceData() {
        onlineNode();
        instances.save(spec("inst-b", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            throw new McpanelBusinessException(
                    McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                    "instance.notFound：节点上不存在该实例的容器/数据。");
        });

        Map<String, Object> listed = service.backup("user:1", "user:1", "inst-b", "list", Map.of());

        // 节点尚无备份目录/记录：按空列表处理，与文件列目录空态同一口径
        assertNotNull(listed.get("backups"));
        assertTrue(((List<?>) listed.get("backups")).isEmpty());
    }

    // ---------- 启动时重建节点声明 ----------

    @Test
    void startRecreatesNodeDeclarationAndRetriesWhenNodeLostInstance() {
        onlineNode();
        instances.save(spec("inst-r1", "paper"));
        List<String> starts = new java.util.ArrayList<>();
        List<String> creates = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.start".equals(method)) {
                starts.add(String.valueOf(payload.get("ik")));
                if (starts.size() == 1) {
                    throw new McpanelBusinessException(
                            McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                            "instance.notFound：节点上不存在该实例的容器/数据。");
                }
                return Map.of("state", "running");
            }
            if ("instance.create".equals(method)) {
                creates.add(String.valueOf(payload.get("ik")));
                return Map.of("state", "created");
            }
            return Map.of();
        });

        Map<String, Object> started = service.action("user:1", "user:1", "inst-r1", "start", null);

        // 节点声明缺失：启动先重发 instance.create 重建声明，再原样重试一次启动成功
        assertEquals("running", started.get("state"));
        assertEquals(2, starts.size());
        assertEquals(1, creates.size());
        assertTrue(creates.get(0).startsWith("panel-rebuild-inst-r1"));
    }

    @Test
    void startStillReportsNotFoundWhenRebuiltStartFailsAgain() {
        onlineNode();
        instances.save(spec("inst-r2", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.start".equals(method)) {
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            return Map.of("state", "created");
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.action("user:1", "user:1", "inst-r2", "start", null));
        // 重建后启动仍失败：按原语义上报，并标注已重建，便于与未重建的报错区分
        assertEquals(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("已自动重建仍不可用"));
    }

    @Test
    void startRebuildsWhenNodeReportsMissingUnderPrivateCode() {
        onlineNode();
        instances.save(spec("inst-r7", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.start".equals(method)) {
                if (starts.incrementAndGet() == 1) {
                    // 节点以私有码上报缺失（如状态 missing 对应码），消息含 instance.notFound：
                    // 经 call() 归一后 code 非标准码，重建仍须触发
                    throw new McpanelBusinessException("instance.missing", 409,
                            "instance.notFound：节点上不存在该实例的容器/数据。可能创建未完成、外部删除或数据卷被清空。");
                }
                return Map.of("state", "running");
            }
            return Map.of("state", "created");
        });

        Map<String, Object> started = service.action("user:1", "user:1", "inst-r7", "start", null);

        // 非标准码但消息即 notFound 文案：同样重建并重试启动
        assertEquals("running", started.get("state"));
        assertEquals(List.of("instance.start", "instance.create", "instance.start"), methods);
    }

    @Test
    void startDoesNotRebuildForOtherNodeErrors() {
        onlineNode();
        instances.save(spec("inst-r3", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            throw new McpanelBusinessException("node.timeout", 504, "节点响应超时");
        });

        assertThrows(McpanelBusinessException.class,
                () -> service.action("user:1", "user:1", "inst-r3", "start", null));
        // 非声明缺失错误不触发重建，避免误发 create
        assertEquals(List.of("instance.start"), methods);
    }

    @Test
    void startFallsBackToUpdateWhenNodeRefusesToOverwriteDeclaration() {
        onlineNode();
        instances.save(spec("inst-r4", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.start".equals(method)) {
                if (starts.incrementAndGet() == 1) {
                    throw new McpanelBusinessException(
                            McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                            "instance.notFound：节点上不存在该实例的容器/数据。");
                }
                return Map.of("state", "running");
            }
            if ("instance.create".equals(method)) {
                // 节点仍保留声明/数据目录：拒绝覆盖（不丢数据）
                throw new McpanelBusinessException("instance.exists", 409, "实例已存在");
            }
            if ("instance.update".equals(method)) {
                return Map.of("state", "exited");
            }
            return Map.of();
        });

        Map<String, Object> started = service.action("user:1", "user:1", "inst-r4", "start", null);

        // create 被拒 → 退回 update 重建容器 → 重试启动成功
        assertEquals("running", started.get("state"));
        assertEquals(List.of("instance.start", "instance.create", "instance.update", "instance.start"), methods);
    }

    @Test
    void startKeepsNotFoundSemanticsWhenRebuildImpossible() {
        onlineNode();
        instances.save(spec("inst-r5", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.start".equals(method)) {
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            if ("instance.create".equals(method)) {
                throw new McpanelBusinessException("instance.exists", 409, "实例已存在");
            }
            throw new McpanelBusinessException("node.error", 409, "镜像不存在，请重新拉取");
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.action("user:1", "user:1", "inst-r5", "start", null));
        // 重建两条路径都不可行：保留原始 notFound 语义，并附上节点原因供诊断
        assertEquals(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("自动重建未完成"));
        assertTrue(error.getMessage().contains("镜像不存在，请重新拉取"));
        assertEquals(List.of("instance.start", "instance.create", "instance.update"), methods);
    }

    @Test
    void startRestoresThroughTrashWhenNodeLostRegistryButKeptData() {
        onlineNode();
        nodeWithTrashListCap();
        instances.save(spec("inst-r8", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        Map<String, Object> restored = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger starts = new AtomicInteger();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.start".equals(method)) {
                if (starts.incrementAndGet() == 1) {
                    throw new McpanelBusinessException(
                            McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                            "instance.notFound：节点上不存在该实例的容器/数据。");
                }
                return Map.of("state", "running");
            }
            if ("instance.create".equals(method)) {
                if (payload.get("fromTrash") == null) {
                    // 数据目录还在：节点拒绝覆盖
                    throw new McpanelBusinessException("instance.exists", 409, "实例已存在");
                }
                restored.putAll(payload);
                return Map.of("state", "created");
            }
            if ("instance.update".equals(method)) {
                // 登记记录丢失：节点找不到可更新的实例
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            if ("instance.delete".equals(method)) {
                return Map.of("deleted", true, "trashed", true);
            }
            if ("trash.list".equals(method)) {
                return Map.of("items", List.of(
                        Map.of("trashId", "inst-r8-m1", "instanceId", "inst-r8")));
            }
            return Map.of();
        });

        Map<String, Object> started = service.action("user:1", "user:1", "inst-r8", "start", null);

        // create 拒覆盖 + update 报缺失 → 回收站往返（delete → trash.list → fromTrash create）→ 启动成功
        assertEquals("running", started.get("state"));
        assertEquals(List.of("instance.start", "instance.create", "instance.update",
                "instance.delete", "trash.list", "instance.create", "instance.start"), methods);
        assertEquals("inst-r8-m1", restored.get("fromTrash"));
    }

    @Test
    void startReportsRestoreFailureAfterTrashRoundTrip() {
        onlineNode();
        nodeWithTrashListCap();
        instances.save(spec("inst-r9", "paper"));
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.start".equals(method)) {
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            if ("instance.create".equals(method)) {
                if (payload.get("fromTrash") == null) {
                    throw new McpanelBusinessException("instance.exists", 409, "实例已存在");
                }
                throw new McpanelBusinessException("node.error", 409, "磁盘空间不足");
            }
            if ("instance.update".equals(method)) {
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            if ("instance.delete".equals(method)) {
                return Map.of("deleted", true, "trashed", true);
            }
            if ("trash.list".equals(method)) {
                return Map.of("items", List.of(
                        Map.of("trashId", "inst-r9-m1", "instanceId", "inst-r9")));
            }
            return Map.of();
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.action("user:1", "user:1", "inst-r9", "start", null));
        // 回收站往返中途失败：保留 notFound 语义并附原因（数据停在节点回收站，可手动找回）
        assertEquals(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("自动重建未完成"));
        assertTrue(error.getMessage().contains("磁盘空间不足"));
    }

    @Test
    void startSurfacesCreateFailureWhenRebuildCannotStart() {
        onlineNode();
        instances.save(spec("inst-r6", "paper"));
        List<String> methods = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.start".equals(method)) {
                throw new McpanelBusinessException(
                        McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                        "instance.notFound：节点上不存在该实例的容器/数据。");
            }
            throw new McpanelBusinessException("node.unreachable", 502, "节点调用失败：连接被拒绝");
        });

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.action("user:1", "user:1", "inst-r6", "start", null));
        // create 本身失败（节点不可达）：如实上报该失败，不谎报 notFound，也不误试 update
        assertEquals("node.unreachable", error.code());
        assertEquals(List.of("instance.start", "instance.create"), methods);
    }

    // ---------- 附加端口开放/回收 ----------

    /** 让节点上报 trash.list 能力（回收站往返重建按能力门禁）。 */
    private void nodeWithTrashListCap() {
        nodes.save(nodes.findById("node-1").orElseThrow().withRuntime(
                online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus.ONLINE,
                "0.7.5", "h", "sess", List.of("trash.list"), "27", null, 1L, 1L));
    }

    /** 让节点上报 port.check 能力（探测门禁按节点 caps 判定）。 */
    private void nodeWithPortCheckCap() {
        nodes.save(nodes.findById("node-1").orElseThrow().withRuntime(
                online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus.ONLINE,
                "0.7.0", "h", "sess", List.of("port.check"), "27", null, 1L, 1L));
    }

    private List<McpanelInstance.PortMapping> instancePorts(String id) {
        return instances.findById(id).orElseThrow().ports();
    }

    @Test
    void addPortAutoAllocatesProbesThenUpdatesNode() {
        onlineNode();
        nodeWithPortCheckCap();
        List<String> methods = new java.util.ArrayList<>();
        Map<String, Object> updatePayload = new java.util.concurrent.ConcurrentHashMap<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            if ("port.check".equals(method)) {
                return Map.of("results", List.of(Map.of("port", 25566, "proto", "tcp", "free", true)));
            }
            if ("instance.update".equals(method)) {
                updatePayload.putAll(payload);
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-port1", "paper"));
        methods.clear();
        Map<String, Object> view = service.addPort("user:1", "user:1", "inst-port1", "tcp", null);
        // 调用顺序：先探测占用，再下发容器重建
        assertEquals(List.of("port.check", "instance.update"), methods);
        assertEquals(2, ((List<?>) updatePayload.get("ports")).size());
        assertEquals(2L, ports.countByNode("node-1"));
        assertEquals(2, ((List<?>) view.get("ports")).size());
    }

    @Test
    void addPortManualConflictKeepsInstanceIntact() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        service.create("user:1", "user:1", spec("inst-pa", "paper"));
        service.create("user:1", "user:1", spec("inst-pb", "paper"));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.addPort("user:1", "user:1", "inst-pa", "tcp", 25566));
        assertEquals("port.conflict", error.code());
        assertEquals(2L, ports.countByNode("node-1"));
        assertEquals(1, instancePorts("inst-pa").size());
    }

    @Test
    void addPortProbeOccupiedRejectsWithoutMutating() {
        onlineNode();
        nodeWithPortCheckCap();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            if ("port.check".equals(method)) {
                return Map.of("results", List.of(Map.of("port", 25570, "proto", "tcp", "free", false,
                        "reason", "地址已在使用中")));
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pc", "paper"));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.addPort("user:1", "user:1", "inst-pc", "tcp", 25570));
        assertEquals("port.conflict", error.code());
        assertTrue(error.getMessage().contains("25570"));
        assertEquals(1L, ports.countByNode("node-1"));
        assertEquals(1, instancePorts("inst-pc").size());
    }

    @Test
    void addPortNodeFailureRollsBackLedgerAndSpec() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            if ("instance.update".equals(method)) {
                throw new McpanelBusinessException("node.offline", 502, "节点离线");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pd", "paper"));
        assertThrows(McpanelBusinessException.class,
                () -> service.addPort("user:1", "user:1", "inst-pd", "tcp", 25570));
        assertEquals(1L, ports.countByNode("node-1"));
        assertEquals(1, instancePorts("inst-pd").size());
    }

    @Test
    void removePortRejectsPrimaryAndUnknown() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        service.create("user:1", "user:1", spec("inst-pe", "paper"));
        assertEquals("invalid-request", assertThrows(McpanelBusinessException.class,
                () -> service.removePort("user:1", "user:1", "inst-pe", 25565, "tcp")).code());
        assertEquals("node-not-found", assertThrows(McpanelBusinessException.class,
                () -> service.removePort("user:1", "user:1", "inst-pe", 25590, "tcp")).code());
        assertEquals(1L, ports.countByNode("node-1"));
    }

    @Test
    void addThenRemovePortRoundTripReleasesLedger() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method) || "instance.update".equals(method)) {
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pf", "paper"));
        service.addPort("user:1", "user:1", "inst-pf", "tcp", 25570);
        assertEquals(2, instancePorts("inst-pf").size());
        assertEquals(2L, ports.countByNode("node-1"));
        Map<String, Object> view = service.removePort("user:1", "user:1", "inst-pf", 25570, "tcp");
        assertEquals(1, instancePorts("inst-pf").size());
        assertEquals(1L, ports.countByNode("node-1"));
        assertEquals(1, ((List<?>) view.get("ports")).size());
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) ((List<?>) view.get("ports")).get(0)).get("primary")));
    }

    @Test
    void removePortNodeFailureRestoresLedgerAndSpec() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            if ("instance.update".equals(method)) {
                // 开放端口（2 ports）成功下发；回收端口（1 port）时节点离线。
                if (((List<?>) payload.get("ports")).size() <= 1) {
                    throw new McpanelBusinessException("node.offline", 502, "节点离线");
                }
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pg", "paper"));
        service.addPort("user:1", "user:1", "inst-pg", "tcp", 25570);
        assertEquals(2L, ports.countByNode("node-1"));
        assertThrows(McpanelBusinessException.class,
                () -> service.removePort("user:1", "user:1", "inst-pg", 25570, "tcp"));
        assertEquals(2L, ports.countByNode("node-1"));
        assertEquals(2, instancePorts("inst-pg").size());
    }

    @Test
    void portChangeRequiresStoppedInstance() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> Map.of("state", "created"));
        service.create("user:1", "user:1", spec("inst-ph", "paper"));
        instances.mutateState("inst-ph", base -> base.withState("running", null, 1L));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.addPort("user:1", "user:1", "inst-ph", "tcp", null));
        assertEquals("instance-running", error.code());
        assertEquals(1L, ports.countByNode("node-1"));
    }

    @Test
    void updateWithPortChangeAllocatesAndReleases() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method) || "instance.update".equals(method)) {
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pi", "paper"));
        McpanelInstance current = instances.findById("inst-pi").orElseThrow();
        McpanelInstance withExtra = current.withPorts(
                List.of(current.ports().get(0), new McpanelInstance.PortMapping(25570, 25570, "tcp")), 1L);
        Map<String, Object> updated = service.update("user:1", "user:1", "inst-pi", withExtra);
        assertEquals(2, ((List<?>) updated.get("ports")).size());
        assertEquals(2L, ports.countByNode("node-1"));
        McpanelInstance withoutExtra = current.withPorts(List.of(current.ports().get(0)), 1L);
        Map<String, Object> updated2 = service.update("user:1", "user:1", "inst-pi", withoutExtra);
        assertEquals(1, ((List<?>) updated2.get("ports")).size());
        assertEquals(1L, ports.countByNode("node-1"));
    }

    @Test
    void updateStrippingPortsFailsExplicitlyInsteadOfWiping() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-pj", "paper"));
        // 旧实例弹窗式整档替换（body 不带 ports → 空列表）：必须显式报错，不再静默清空端口。
        McpanelInstance stripped = McpanelInstance.create("inst-pj", "node-1", "实例inst-pj", "paper",
                "1.21.4", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L);
        assertThrows(McpanelBusinessException.class,
                () -> service.update("user:1", "user:1", "inst-pj", stripped));
        assertEquals(1L, ports.countByNode("node-1"));
        assertEquals(1, instancePorts("inst-pj").size());
    }

    // ---------- 关闭全部端口开放 ----------

    @Test
    void closeAllPortsClearsSpecLedgerAndSendsEmptyPortsPayload() {
        onlineNode();
        List<String> methods = new java.util.ArrayList<>();
        List<String> audits = new java.util.ArrayList<>();
        Map<String, Object> updatePayload = new java.util.concurrent.ConcurrentHashMap<>();
        McpanelInstanceAppService service = new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> {
                    methods.add(method);
                    if ("instance.create".equals(method)) {
                        return CompletableFuture.completedFuture(Map.of("state", "created"));
                    }
                    if ("instance.update".equals(method)) {
                        updatePayload.putAll(payload);
                    }
                    return CompletableFuture.completedFuture(Map.of("state", "created"));
                },
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> audits.add(action + ":" + detail),
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
        service.create("user:1", "user:1", spec("inst-close1", "paper"));
        service.addPort("user:1", "user:1", "inst-close1", "tcp", 25570);
        assertEquals(2L, ports.countByNode("node-1"));
        methods.clear();
        audits.clear();

        Map<String, Object> view = service.closeAllPorts("user:1", "user:1", "inst-close1");

        // 容器按零发布端口重建；台账全部释放；视图为空列表
        assertEquals(List.of("instance.update"), methods);
        assertTrue(((List<?>) updatePayload.get("ports")).isEmpty());
        assertTrue(String.valueOf(updatePayload.get("ik")).startsWith("panel-ports-close-inst-close1"));
        assertEquals(0L, ports.countByNode("node-1"));
        assertTrue(instancePorts("inst-close1").isEmpty());
        assertTrue(((List<?>) view.get("ports")).isEmpty());
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).startsWith("instance.ports.close:"));
    }

    @Test
    void closeAllPortsRequiresStoppedAndIsIdempotentWhenEmpty() {
        onlineNode();
        List<String> methods = new java.util.ArrayList<>();
        McpanelInstanceAppService service = service((method, payload) -> {
            methods.add(method);
            return Map.of("state", "created");
        });
        service.create("user:1", "user:1", spec("inst-close2", "paper"));
        instances.mutateState("inst-close2", base -> base.withState("running", null, 1L));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.closeAllPorts("user:1", "user:1", "inst-close2"));
        assertEquals("instance-running", error.code());
        assertEquals(1L, ports.countByNode("node-1"));

        // 已关闭（无端口）时幂等返回视图，不再下发节点
        instances.mutateState("inst-close2", base -> base.withState("exited", null, 1L));
        methods.clear();
        instances.save(instances.findById("inst-close2").orElseThrow().withPorts(List.of(), 1L));
        Map<String, Object> view = service.closeAllPorts("user:1", "user:1", "inst-close2");
        assertTrue(methods.isEmpty());
        assertTrue(((List<?>) view.get("ports")).isEmpty());
    }

    @Test
    void closeAllPortsNodeFailureRestoresLedgerAndSpec() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method)) {
                return Map.of("state", "created");
            }
            if ("instance.update".equals(method)) {
                throw new McpanelBusinessException("node.offline", 502, "节点离线");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-close3", "paper"));

        assertThrows(McpanelBusinessException.class,
                () -> service.closeAllPorts("user:1", "user:1", "inst-close3"));

        // 节点下发失败：聚合端口与台账全部回滚，端口未释放
        assertEquals(1, instancePorts("inst-close3").size());
        assertEquals(1L, ports.countByNode("node-1"));
    }

    @Test
    void closedInstanceCanReopenPortAsNewPrimary() {
        onlineNode();
        McpanelInstanceAppService service = service((method, payload) -> {
            if ("instance.create".equals(method) || "instance.update".equals(method)) {
                return Map.of("state", "created");
            }
            return Map.of();
        });
        service.create("user:1", "user:1", spec("inst-close4", "paper"));
        service.closeAllPorts("user:1", "user:1", "inst-close4");
        assertEquals(0, instancePorts("inst-close4").size());

        // 重新开放：走既有 addPort，新分配端口成为新的主端口
        Map<String, Object> view = service.addPort("user:1", "user:1", "inst-close4", "tcp", null);
        assertEquals(1, instancePorts("inst-close4").size());
        assertEquals(1L, ports.countByNode("node-1"));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) ((List<?>) view.get("ports")).get(0)).get("primary")));
    }
}
