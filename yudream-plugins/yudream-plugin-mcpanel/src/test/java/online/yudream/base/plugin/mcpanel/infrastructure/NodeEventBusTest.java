package online.yudream.base.plugin.mcpanel.infrastructure;

import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NodeEventBus 功能正确性（主审 1-5 项）：按 delegate 注销、订阅原子性、
 * disposed 门闸、首屏仅最新快照。
 */
class NodeEventBusTest {

    private static final class CountingSubscriber implements PluginSseStream.Subscriber {

        final String name;
        final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        final AtomicInteger completed = new AtomicInteger();

        CountingSubscriber(String name) {
            this.name = name;
        }

        @Override
        public void send(String event, Object payload) {
            received.add(event + "#" + payload);
        }

        @Override
        public void complete() {
            completed.incrementAndGet();
        }

        @Override
        public void error(Throwable error) {
            received.add("error");
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timeout: " + what);
    }

    @Test
    void unsubscribeByDelegateStopsDeliveryAndDoesNotLeak() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        CountingSubscriber subscriber = new CountingSubscriber("a");
        bus.open("node-a", null).subscribe(subscriber);
        await("connected delivered", () -> subscriber.received.stream().anyMatch(e -> e.startsWith("connected")));
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-a", Map.of("status", "online"));
        await("state delivered", () -> subscriber.received.stream().anyMatch(e -> e.startsWith("node.state")));
        bus.unsubscribe(subscriber);
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-a", Map.of("status", "offline"));
        Thread.sleep(300);
        int afterUnsubscribe = subscriber.received.size();
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-a", Map.of("status", "offline"));
        Thread.sleep(300);
        assertEquals(afterUnsubscribe, subscriber.received.size(), "注销后不得再投递");
    }

    @Test
    void subscribeIsAtomicAgainstPublish() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        CountingSubscriber subscriber = new CountingSubscriber("b");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch done = new CountDownLatch(1);
            pool.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-b", Map.of("status", "online", "i", i));
                }
                done.countDown();
            });
            bus.open("node-b", null).subscribe(subscriber);
            done.await(5, TimeUnit.SECONDS);
            final boolean[] got = {false};
            await("post-subscribe event arrives", () -> {
                if (!got[0] && subscriber.received.stream().anyMatch(e -> e.contains("i=499"))) {
                    got[0] = true;
                }
                return got[0];
            });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completeAllGatesNewSubscriptions() {
        NodeEventBus bus = new NodeEventBus();
        CountingSubscriber first = new CountingSubscriber("c1");
        bus.open("node-c", null).subscribe(first);
        bus.completeAll();
        assertEquals(1, first.completed.get());
        CountingSubscriber late = new CountingSubscriber("c2");
        bus.open("node-c", null).subscribe(late);
        assertEquals(1, late.completed.get(), "disposed 后新订阅立即 complete");
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-c", Map.of("status", "online"));
        assertTrue(late.received.isEmpty());
    }

    @Test
    void freshSubscribeGetsOnlyLatestSnapshot() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        for (int i = 0; i < 5; i++) {
            bus.publish(NodeEventBus.TYPE_NODE_STATS, "node-d", Map.of("cpuPercent", i));
            bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-d", Map.of("status", "online", "i", i));
        }
        CountingSubscriber subscriber = new CountingSubscriber("d");
        bus.open("node-d", null).subscribe(subscriber);
        await("latest snapshot delivered", () ->
                subscriber.received.stream().anyMatch(e -> e.startsWith("node.stats") && e.contains("cpuPercent=4")));
        long statsBacklog = subscriber.received.stream().filter(e -> e.startsWith("node.stats")).count();
        assertEquals(1, statsBacklog, "首屏只补最新一条 stats");
    }

    @Test
    void cursorSubscribeReplaysRingForNode() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-e", Map.of("status", "online"));
        long cursor = 1L;
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-e", Map.of("status", "offline"));
        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-other", Map.of("status", "online"));
        CountingSubscriber subscriber = new CountingSubscriber("e");
        bus.open("node-e", cursor).subscribe(subscriber);
        await("cursor replay", () -> subscriber.received.stream()
                .anyMatch(e -> e.startsWith("node.state") && e.contains("status=offline")));
        assertEquals(1, subscriber.received.stream()
                .filter(e -> e.startsWith("node.state")).count());
        assertTrue(subscriber.received.stream().noneMatch(e -> e.contains("node-other")
                || (e.startsWith("node.state") && e.contains("status=online"))));
    }

    // ---------- 过滤流 / 预算 / 溢出契约（主审回归） ----------

    /** 记录事件类型的订阅者。 */
    private static boolean types(ConcurrentLinkedQueue<String> received, String type) {
        return received.stream().filter(e -> e.startsWith(type + "#")).count() > 0;
    }

    private static long countType(ConcurrentLinkedQueue<String> received, String type) {
        return received.stream().filter(e -> e.startsWith(type + "#")).count();
    }

    @Test
    void filteredStreamUnsubscribeByRawStopsDelivery() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        CountingSubscriber subscriber = new CountingSubscriber("f1");
        PluginSseStream stream = bus.openFiltered("node-f", "instance.output", "instanceId", "i-1");
        stream.subscribe(subscriber);
        await("filtered connected", () -> types(subscriber.received, "connected"));
        bus.publish("instance.output", "node-f", Map.of("instanceId", "i-1", "text", "a"));
        await("matching delivered", () -> types(subscriber.received, "instance.output"));
        // 宿主以原始订阅者注销：包装器不得造成登记泄漏（旧实现按 delegate 匹配失效）。
        stream.unsubscribe(subscriber);
        Thread.sleep(300);
        long after = subscriber.received.size();
        bus.publish("instance.output", "node-f", Map.of("instanceId", "i-1", "text", "b"));
        Thread.sleep(300);
        assertEquals(after, subscriber.received.size(), "注销后不得再投递（登记必须被摘除）");
    }

    @Test
    void filteredStreamStrictMatchingAndControlPassthrough() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        CountingSubscriber subscriber = new CountingSubscriber("f2");
        bus.openFiltered("node-f2", "instance.output", "instanceId", "i-1").subscribe(subscriber);
        await("connected delivered", () -> types(subscriber.received, "connected"));
        bus.publish("instance.output", "node-f2", Map.of("instanceId", "i-1", "text", "hit"));
        bus.publish("instance.output", "node-f2", Map.of("instanceId", "i-2", "text", "other-instance"));
        bus.publish("instance.output", "node-f2", new HashMap<>()); // 缺 matchKey：严格丢弃
        bus.publish("instance.output", "node-f2", Map.of()); // 缺 matchKey：严格丢弃
        bus.publish("node.stats", "node-f2", Map.of("instanceId", "i-1", "cpuPercent", 1)); // 类型不符
        bus.publish(NodeEventBus.TYPE_HEARTBEAT, null, Map.of()); // 控制事件必须放行
        await("hit delivered", () -> countType(subscriber.received, "instance.output") >= 1);
        await("heartbeat delivered", () -> countType(subscriber.received, "heartbeat") >= 1);
        Thread.sleep(200);
        assertEquals(1, countType(subscriber.received, "instance.output"), "只放行严格匹配的数据事件");
        assertEquals(0, countType(subscriber.received, "node.stats"), "类型不符必须丢弃");
    }

    @Test
    void overflowNotifiesOnlyThatSubscriptionScopeViaStreamGap() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        CountingSubscriber blocked = new CountingSubscriber("g-blocked");
        CountingSubscriber observer = new CountingSubscriber("g-observer");
        bus.open("node-g", null).subscribe(blocked);
        bus.open("node-g", null).subscribe(observer);
        await("connected delivered", () -> types(blocked.received, "connected"));
        // connected 已被排空；队列容量 256：300 条事件入队 → 溢出丢最旧 44 条。
        for (int i = 0; i < 300; i++) {
            bus.publish("instance.output", "node-g", Map.of("instanceId", "i-1", "line", i));
        }
        await("backlog drained", () -> countType(observer.received, "instance.output") >= 250);
        assertTrue(types(observer.received, "stream.gap"), "真实溢出必须向该订阅发 stream.gap");
        String gap = observer.received.stream().filter(e -> e.startsWith("stream.gap#")).findFirst().orElseThrow();
        assertTrue(gap.contains("dropped=44"), "gap 必须携带丢弃计数: " + gap);
        assertTrue(gap.contains("node-g"), "gap 必须绑定订阅 scope 的 nodeId");
        assertTrue(gap.contains("instance.output"), "gap 必须携带 topic 分布");
        assertTrue(observer.received.stream().noneMatch(e -> e.contains("line=0")), "最旧事件应被丢弃");
        assertTrue(observer.received.stream().anyMatch(e -> e.contains("line=299")), "最新事件必须保留");
        assertTrue(countType(observer.received, "instance.output") <= 256, "投递量不得超过队列容量");
    }

    @Test
    void slowDrainKeepsBacklogWithoutGap() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        SlowSubscriber slow = new SlowSubscriber(25);
        bus.open("node-slow", null).subscribe(slow);
        await("connected delivered", () -> types(slow.received, "connected"));
        for (int i = 0; i < 20; i++) {
            bus.publish("instance.output", "node-slow", Map.of("instanceId", "i-1", "line", i));
        }
        // 独立预算：慢订阅者分多轮缓慢排空，但绝不丢队、绝不发 gap（没有真实丢失）。
        await("all events eventually delivered", () -> countType(slow.received, "instance.output") >= 20);
        assertEquals(0, countType(slow.received, "stream.gap"), "预算到期不是丢失，不得伪称 gap");
    }

    @Test
    void freshSubscribeSnapshotExcludesHighFrequencyEvents() throws Exception {
        NodeEventBus bus = new NodeEventBus();

        bus.publish(NodeEventBus.TYPE_NODE_STATE, "node-l", Map.of("status", "online"));
        bus.publish(NodeEventBus.TYPE_NODE_STATS, "node-l", Map.of("cpuPercent", 3));
        bus.publish("instance.output", "node-l", Map.of("instanceId", "i-9", "text", "log"));
        CountingSubscriber subscriber = new CountingSubscriber("l");
        bus.open("node-l", null).subscribe(subscriber);
        await("snapshot delivered", () -> types(subscriber.received, "node.state")
                && types(subscriber.received, "node.stats"));
        assertEquals(0, countType(subscriber.received, "instance.output"),
                "日志类事件不得作为首屏快照补发");
    }

    /** 每次投递固定耗时的慢订阅者（验证独立预算下不丢队）。 */
    private static final class SlowSubscriber implements PluginSseStream.Subscriber {

        private final long delayMs;
        final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();

        SlowSubscriber(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void send(String event, Object payload) {
            received.add(event + "#" + payload);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void complete() {
            // no-op
        }

        @Override
        public void error(Throwable error) {
            received.add("error");
        }
    }
}
