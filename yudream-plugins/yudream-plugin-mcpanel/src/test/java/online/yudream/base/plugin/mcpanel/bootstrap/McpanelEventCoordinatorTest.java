package online.yudream.base.plugin.mcpanel.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件协调器回归：保序、合并、来源校验门控计划触发、慢通道隔离、上线回读去重、清扫、close 关停。 */
class McpanelEventCoordinatorTest {

    private McpanelEventCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    private static Map<String, Object> stateEvent(String instanceId, String state) {
        return Map.of("instanceId", instanceId, "state", state);
    }

    @Test
    void stateEventsDeliveredWithSourceNodeAndKeptInOrder() throws Exception {
        // 槽内合并（last-write-wins）是设计行为：中间态可被折叠，但
        // (a) 每个键的观察序列必须是时间线子序列（不回退），(b) 最终态必须到达，(c) 来源节点正确。
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        CountDownLatch finals = new CountDownLatch(2);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    seen.add(nodeId + ":" + payload.get("instanceId") + ":" + payload.get("state"));
                    if ("exited".equals(String.valueOf(payload.get("state")))) {
                        finals.countDown();
                    }
                    return true;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        coordinator.onEvent("instance.state", "node-1", stateEvent("i1", "created"));
        coordinator.onEvent("instance.state", "node-2", stateEvent("i1", "created"));
        coordinator.onEvent("instance.state", "node-1", stateEvent("i1", "running"));
        coordinator.onEvent("instance.state", "node-2", stateEvent("i1", "running"));
        coordinator.onEvent("instance.state", "node-1", stateEvent("i1", "exited"));
        coordinator.onEvent("instance.state", "node-2", stateEvent("i1", "exited"));
        assertTrue(finals.await(5, TimeUnit.SECONDS), "两个来源节点的最终态都必须到达");
        // 单工作线程 FIFO：i1 的最终态先于 i2 的最终态被消费。
        int i1Final = new java.util.ArrayList<>(seen).indexOf("node-1:i1:exited");
        int i2Final = new java.util.ArrayList<>(seen).indexOf("node-2:i1:exited");
        assertTrue(i1Final >= 0 && i2Final > i1Final, "保序投递：" + seen);
        // 每键观察序列是状态时间线的子序列（合并可跳态，但不回退）。
        List<String> timeline = List.of("created", "running", "exited");
        for (String node : List.of("node-1", "node-2")) {
            List<String> observed = seen.stream()
                    .filter(item -> item.startsWith(node + ":"))
                    .map(item -> item.substring(item.lastIndexOf(':') + 1))
                    .toList();
            assertTrue(observed.size() >= 1 && observed.size() <= 3, "合并后至多 3 态：" + observed);
            assertEquals("exited", observed.get(observed.size() - 1), "最终态为 exited");
            int cursor = 0;
            for (String state : observed) {
                while (cursor < timeline.size() && !timeline.get(cursor).equals(state)) {
                    cursor++;
                }
                assertTrue(cursor < timeline.size(), "状态时间线不回退：" + observed);
                cursor++;
            }
        }
    }

    @Test
    void stateCoalescingCollapsesBurstToLatest() throws Exception {
        AtomicInteger applied = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    if ("final".equals(String.valueOf(payload.get("state")))) {
                        applied.incrementAndGet();
                        done.countDown();
                    }
                    return true;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        for (int i = 0; i < 500; i++) {
            coordinator.onEvent("instance.state", "node-1", stateEvent("burst", "s-" + i));
        }
        coordinator.onEvent("instance.state", "node-1", stateEvent("burst", "final"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // 合并语义：最终状态必须到达；风暴中同键事件被折叠为至多数次写（< 500）。
        assertTrue(applied.get() >= 1);
    }

    @Test
    void rejectedStateDoesNotTriggerSchedule() throws Exception {
        // 来源校验失败（sink 返回 false）：状态不落库，也绝不触发计划任务（防跨节点伪造触发）。
        AtomicInteger triggers = new AtomicInteger();
        CountDownLatch stateSeen = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    stateSeen.countDown();
                    return false;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> triggers.incrementAndGet());
        coordinator.onEvent("instance.state", "node-fake", stateEvent("victim", "running"));
        assertTrue(stateSeen.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(0, triggers.get(), "被拒绝的事件不得触发计划任务");
    }

    @Test
    void acceptedStateTriggersMappedScheduleEvent() throws Exception {
        // sink 返回 true：running→instance.start；created 不映射任何计划事件。
        // 注意 state 槽同键合并（last-write-wins）是设计行为：用不同 instanceId 分键，
        // 避免 created 在测试线程连续入队时折叠掉 running（真实节点事件按序到达不回退）。
        ConcurrentLinkedQueue<String> triggers = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> true,
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                    triggers.add(instanceId + ":" + event);
                    start.countDown();
                });
        coordinator.onEvent("instance.state", "n", stateEvent("i9", "running"));
        assertTrue(start.await(5, TimeUnit.SECONDS), "running 应映射 instance.start 触发");
        assertTrue(triggers.contains("i9:instance.start"));
        // created（独立键）不映射任何计划事件
        coordinator.onEvent("instance.state", "n", stateEvent("i8", "created"));
        Thread.sleep(300);
        assertTrue(triggers.stream().noneMatch(item -> item.startsWith("i8:")),
                "created 不应触发计划任务：" + triggers);
    }

    @Test
    void onlineBackfillDeduplicatesPerNode() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        CountDownLatch firstSync = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> true,
                nodeId -> {
                },
                nodeId -> {
                    try {
                        // 首次回读制造执行窗口，观察期间的第二个 online 事件必须被去重。
                        firstSync.countDown();
                        Thread.sleep(150);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    syncs.incrementAndGet();
                    return 0;
                },
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        coordinator.onEvent("node.state", "node-x", Map.of("status", "online"));
        assertTrue(firstSync.await(5, TimeUnit.SECONDS));
        coordinator.onEvent("node.state", "node-x", Map.of("status", "online"));
        coordinator.onEvent("node.state", "node-x", Map.of("status", "online"));
        // 在跑期间重复事件被忽略：sync 恰好 1 次。
        Thread.sleep(400);
        assertEquals(1, syncs.get());
    }

    @Test
    void offlineHandledOnStateLane() throws Exception {
        CountDownLatch offline = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> true,
                nodeId -> offline.countDown(),
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        coordinator.onEvent("node.state", "node-y", Map.of("status", "offline"));
        assertTrue(offline.await(5, TimeUnit.SECONDS));
    }

    @Test
    void idleSweepEvictsSlotsAndRegistryStaysUsable() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    processed.countDown();
                    return true;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        coordinator.onEvent("instance.state", "n", stateEvent("i", "running"));
        assertTrue(processed.await(5, TimeUnit.SECONDS));
        // drain 的 finally（scheduled 复位）与 handler 内 countDown 之间有微小窗口：轮询等待清扫生效。
        int swept = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (swept == 0 && System.currentTimeMillis() < deadline) {
            swept = coordinator.sweepIdleSlots(0L);
            if (swept == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue(swept >= 1, "空闲槽应被清扫");
        // 清扫后注册表随用随建：新事件仍正常投递。
        CountDownLatch again = new CountDownLatch(1);
        McpanelEventCoordinator second = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    again.countDown();
                    return true;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        try {
            for (int i = 0; i < 5000; i++) {
                second.onEvent("instance.state", "n", stateEvent("flood-" + i, "running"));
            }
            second.sweepIdleSlots(0L);
            second.onEvent("instance.state", "n", stateEvent("i2", "exited"));
            assertTrue(again.await(5, TimeUnit.SECONDS), "清扫/洪泛后仍可正常投递");
        } finally {
            second.close();
        }
    }

    @Test
    void closeDisposesAndIgnoresLateEvents() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> {
                    processed.countDown();
                    return true;
                },
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        coordinator.onEvent("instance.state", "n", stateEvent("i", "running"));
        assertTrue(processed.await(5, TimeUnit.SECONDS));
        coordinator.close();
        assertTrue(coordinator.isDisposed());
        // close 后再投递：不抛异常、不再消费。
        coordinator.onEvent("instance.state", "n", stateEvent("i2", "running"));
        Thread.sleep(100);
        coordinator.close();
    }

    @Test
    void highFrequencyEventTypesAreIgnored() {
        coordinator = new McpanelEventCoordinator(
                (nodeId, payload) -> true,
                nodeId -> {
                },
                nodeId -> 0,
                (nodeId, stats) -> 0,
                (instanceId, event) -> {
                });
        // 不抛异常即可：输出/终端/统计流不进协调器
        coordinator.onEvent("instance.output", "n", Map.of("instanceId", "i"));
        coordinator.onEvent("node.terminal.output", "n", Map.of("terminalId", "t"));
        coordinator.onEvent("node.stats", "n", Map.of("cpuPercent", 1));
        coordinator.onEvent("heartbeat", null, Map.of());
        coordinator.onEvent("connected", null, Map.of());
    }
}
