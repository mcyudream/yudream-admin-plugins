package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件触发型任务回归：意外退出自动重启、手动停止意图一次性抑制、开关关闭旁路、
 * 状态新鲜度门、快速崩溃熔断与手动启动恢复、节点上线只拉起未运行实例、失败审计。
 */
class InstanceEventTaskServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final RecordingStartGateway gateway = new RecordingStartGateway();
    private final List<String> audits = new CopyOnWriteArrayList<>();

    private InstanceEventTaskService service;

    @BeforeEach
    void clean() {
        documents.clear();
        gateway.methods.clear();
        gateway.instanceIds.clear();
        gateway.failNext = false;
        audits.clear();
        service = new InstanceEventTaskService(instances, gateway,
                (actor, action, targetType, targetId, detail, tenantId) -> audits.add(action), 0L);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    // ---------- 桩与工具 ----------

    /** 记录调用的启动网关：默认应答 running，可注入失败。 */
    static final class RecordingStartGateway implements InstanceEventTaskService.StartGateway {

        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<String> instanceIds = new CopyOnWriteArrayList<>();
        volatile boolean failNext;

        @Override
        public CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                           Map<String, Object> payload, long timeoutMs) {
            methods.add(method);
            instanceIds.add(String.valueOf(payload.get("instanceId")));
            if (failNext) {
                return CompletableFuture.failedFuture(new RuntimeException("node.unreachable：节点失联"));
            }
            return CompletableFuture.completedFuture(Map.of("state", "running"));
        }

        int count(String method) {
            return (int) methods.stream().filter(method::equals).count();
        }
    }

    private McpanelInstance saveInstance(String id, String nodeId, boolean autoRestart,
                                         boolean autoStart, String state) {
        McpanelInstance instance = McpanelInstance.create(id, nodeId, "实例" + id, "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L)
                .withState(state, null, 1L)
                .withEventTask(autoRestart, autoStart, 1L);
        instances.save(instance);
        return instance;
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void quiesce() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 自动重启 ----------

    @Test
    void unexpectedExitTriggersAutoRestartAndWritesBackState() {
        saveInstance("i1", "node-1", true, false, "exited");
        service.onInstanceExited("i1");
        await(() -> gateway.count("instance.start") == 1, "意外退出应触发一次自动重启");
        assertEquals(List.of("i1"), gateway.instanceIds, "自动重启只针对退出实例");
        assertEquals("running", instances.findById("i1").orElseThrow().state(), "启动应答状态应回写");
        assertTrue(audits.contains("instance.event-task.restart"), "自动重启应有审计");
    }

    @Test
    void manualStopSuppressesOnceThenNextUnexpectedCrashRestarts() {
        saveInstance("i1", "node-1", true, false, "exited");
        service.markManualStop("i1");
        service.onInstanceExited("i1");
        quiesce();
        assertEquals(0, gateway.count("instance.start"), "窗口内的手动停止不得自动重启");
        // 意图已消费：此后真实崩溃（状态重新落为 exited）正常触发。
        instances.mutateState("i1", current -> current.withState("exited", 1, 2L));
        service.onInstanceExited("i1");
        await(() -> gateway.count("instance.start") == 1, "意图消费后的意外退出应触发自动重启");
    }

    @Test
    void autoRestartDisabledDoesNothing() {
        saveInstance("i1", "node-1", false, true, "exited");
        service.onInstanceExited("i1");
        quiesce();
        assertEquals(0, gateway.count("instance.start"), "自动重启关闭时不触发");
    }

    @Test
    void freshStateNoLongerExitedSkipsRestart() {
        // 事件落库后被并发操作拉起（DB 已是 running）：新鲜度门直接跳过。
        saveInstance("i1", "node-1", true, false, "running");
        service.onInstanceExited("i1");
        quiesce();
        assertEquals(0, gateway.count("instance.start"), "实例已运行时不得重复拉起");
    }

    @Test
    void fastCrashBreakerSuspendsUntilManualStart() {
        saveInstance("i1", "node-1", true, false, "exited");
        service.onInstanceExited("i1");
        await(() -> gateway.count("instance.start") == 1, "第 1 次拉起");
        for (int expected = 2; expected <= 3; expected++) {
            instances.mutateState("i1", current -> current.withState("exited", 1, 2L));
            service.onInstanceExited("i1");
            int target = expected;
            await(() -> gateway.count("instance.start") == target, "第 " + target + " 次拉起");
        }
        // 第 4、5 次退出：连续 3 次快速崩溃（<60s）→ 熔断，不再拉起。
        for (int i = 0; i < 2; i++) {
            instances.mutateState("i1", current -> current.withState("exited", 1, 2L));
            service.onInstanceExited("i1");
        }
        quiesce();
        assertEquals(3, gateway.count("instance.start"), "熔断后不得继续自动重启");
        assertTrue(audits.contains("instance.event-task.suspend"), "熔断应有审计可见");
        // 手动启动复位熔断：下一次意外退出恢复自动重启。
        service.onManualStart("i1");
        instances.mutateState("i1", current -> current.withState("exited", 1, 2L));
        service.onInstanceExited("i1");
        await(() -> gateway.count("instance.start") == 4, "手动启动后熔断恢复");
    }

    @Test
    void autoStartFailureIsAuditedNotThrown() {
        saveInstance("i1", "node-1", true, false, "exited");
        gateway.failNext = true;
        service.onInstanceExited("i1");
        await(() -> audits.contains("instance.event-task.restart.failed"), "启动失败应有失败审计");
        assertEquals(1, gateway.count("instance.start"));
        assertEquals("exited", instances.findById("i1").orElseThrow().state(), "失败不改状态");
    }

    // ---------- 节点上线自动启动 ----------

    @Test
    void nodeOnlineStartsOnlyStoppedAutoStartInstances() {
        saveInstance("a", "node-1", false, true, "exited");
        saveInstance("b", "node-1", false, false, "exited");
        saveInstance("c", "node-1", false, true, "running");
        saveInstance("d", "node-2", false, true, "exited");
        service.onNodeOnline("node-1");
        await(() -> gateway.count("instance.start") == 1, "只拉起节点上未运行的 autoStart 实例");
        assertEquals(List.of("a"), gateway.instanceIds, "只应拉起实例 a");
        assertEquals("running", instances.findById("a").orElseThrow().state());
        assertTrue(audits.contains("instance.event-task.start"), "自动启动应有审计");
    }

    @Test
    void nodeOnlineBackfillFailureFallsBackToCreatedState() {
        // created 状态（容器存在但从未运行）也属于可拉起范围。
        saveInstance("a", "node-1", false, true, "created");
        service.onNodeOnline("node-1");
        await(() -> gateway.count("instance.start") == 1, "created 实例应被拉起");
    }
}
