package online.yudream.base.plugin.mcpanel.infrastructure.node;

import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 节点事件总线（SSE，浏览器面）。
 *
 * 并发与生命周期模型：
 * - sequence 生成与入队同在总线锁内：并发 publish 对每个订阅者严格按发布序投递；
 * - subscribe/unsubscribe/completeAll 同锁原子完成（快照、登记、注销、清队）；
 * - 单 dispatcher 线程按序派发；每个订阅者每轮拥有独立时间预算，超时仅暂停本轮、
 *   保留积压（队列本身有界），绝不丢队、绝不误报丢失；单个慢/失效订阅者不阻塞
 *   其他订阅者与 publish；
 * - 溢出（队列满丢最旧）按订阅 scope 计数，并以 stream.gap 控制事件一次性通知
 *   该订阅者（dropped 计数 + topics 分布）；gap 只在真实丢失时发出，不伪称丢失；
 * - unsubscribe 按 rawSubscriber（宿主持有的原始订阅者）匹配，filtered 流的
 *   包装器不会造成登记泄漏；
 * - 最后一个订阅注销/completeAll 释放 dispatcher 线程，杜绝插件 reload 线程泄漏；
 * - completeAll 置 disposed 门闸、shutdownNow dispatcher、complete 全部 delegate；
 *   之后 subscribe 立即 complete，杜绝插件 disable 后复活；
 * - 无 Last-Event-ID 的首屏只补该节点最新 node.state/node.stats 各一条（快照语义）；
 *   带游标订阅按 ring 缓存续传；latestPerNode 仅缓存 state/stats，不缓存日志类事件。
 * 事件类型：connected / heartbeat / node.state / node.stats / stream.gap；
 * envelope 统一 {id,type,at,payload}。
 */
public class NodeEventBus {

    public static final String TYPE_CONNECTED = "connected";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_NODE_STATE = "node.state";
    public static final String TYPE_NODE_STATS = "node.stats";
    /** 节点回报的 P2P 会话状态（connecting/direct/relayed/failed/closed + 候选与字节数）。 */
    public static final String TYPE_P2P_SESSION_STATE = "p2p.session.state";
    /** 溢出丢弃通知（仅投递给发生丢弃的订阅 scope；payload 含 dropped/topics/reason）。 */
    public static final String TYPE_STREAM_GAP = "stream.gap";

    private static final int REPLAY_CAPACITY = 1000;
    private static final int PER_SUBSCRIBER_QUEUE_CAPACITY = 256;
    private static final long HEARTBEAT_PERIOD_MS = 25_000L;
    /** 单订阅者单轮派发时间预算：超时仅暂停本轮，保留积压下一轮继续（不丢队）。 */
    private static final long DRAIN_BUDGET_MS = 200L;

    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final Deque<BufferedEvent> replay = new ConcurrentLinkedDeque<>();
    /** 每节点最新 state/stats 快照（仅此两类），供无游标首屏订阅补发。 */
    private final Map<String, Map<String, BufferedEvent>> latestPerNode = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledExecutorService dispatcher;
    private boolean disposed;

    private final class Entry {

        final String nodeId;
        /** 宿主持有的原始订阅者：注销匹配键（filtered 流下 != delegate）。 */
        final PluginSseStream.Subscriber rawSubscriber;
        /** 实际投递目标（filtered 流为包装器）。 */
        final PluginSseStream.Subscriber delegate;
        /** gap 契约回显的过滤键值（无过滤为 null），前端据此定位订阅 scope。 */
        final String filterKey;
        final String filterValue;
        final Deque<BufferedEvent> queue = new ArrayDeque<>();
        /** 溢出丢弃计数（按 topic 累计）；派发时以一条 stream.gap 一次性通知并清零。 */
        final Map<String, Long> droppedByTopic = new HashMap<>();
        boolean closed;

        Entry(String nodeId, PluginSseStream.Subscriber rawSubscriber, PluginSseStream.Subscriber delegate,
              String filterKey, String filterValue) {
            this.nodeId = nodeId;
            this.rawSubscriber = rawSubscriber;
            this.delegate = delegate;
            this.filterKey = filterKey;
            this.filterValue = filterValue;
        }

        /** 总线锁内调用：有界入队；溢出丢最旧并计数（gap 延迟到派发线程发出，避免入队递归）。 */
        void offerLocked(BufferedEvent event) {
            while (queue.size() >= PER_SUBSCRIBER_QUEUE_CAPACITY) {
                BufferedEvent dropped = queue.pollFirst();
                if (dropped != null) {
                    droppedByTopic.merge(dropped.type(), 1L, Long::sum);
                }
            }
            queue.addLast(event);
        }

        /** 派发线程调用：独立预算内有序发送；预算耗尽仅暂停（保留积压）；返回 false 表示失效需注销。 */
        boolean drain(long deadlineMs) {
            while (true) {
                BufferedEvent event;
                synchronized (lock) {
                    if (closed) {
                        return false;
                    }
                    event = takeLocked();
                }
                if (event == null) {
                    return true;
                }
                try {
                    delegate.send(event.type(), event.payload());
                } catch (RuntimeException error) {
                    try {
                        delegate.error(error);
                    } catch (RuntimeException ignored) {
                        // 宿主 emitter 已失效
                    }
                    return false;
                }
                if (System.currentTimeMillis() > deadlineMs) {
                    // 预算耗尽：仅暂停本轮，积压原样保留，下一轮继续；不丢队、不发 gap。
                    return true;
                }
            }
        }

        /** 总线锁内调用：真实溢出优先合成 stream.gap（只报真实丢失），再取数据事件。 */
        private BufferedEvent takeLocked() {
            if (!droppedByTopic.isEmpty()) {
                BufferedEvent gap = new BufferedEvent(TYPE_STREAM_GAP, gapEnvelopeLocked(new HashMap<>(droppedByTopic)));
                droppedByTopic.clear();
                return gap;
            }
            return queue.pollFirst();
        }

        private Map<String, Object> gapEnvelopeLocked(Map<String, Long> droppedByTopic) {
            long id = sequence.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", "overflow");
            long total = 0L;
            for (Long count : droppedByTopic.values()) {
                total += count;
            }
            body.put("dropped", total);
            body.put("topics", droppedByTopic);
            if (filterKey != null) {
                body.put("filterKey", filterKey);
                body.put("filterValue", filterValue);
            }
            return envelope(id, TYPE_STREAM_GAP, nodeId, body);
        }
    }

    public void subscribe(PluginSseStream.Subscriber subscriber) {
        open(null, null).subscribe(subscriber);
    }

    public void unsubscribe(PluginSseStream.Subscriber subscriber) {
        Entry removed = null;
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                // 按 rawSubscriber 匹配（filtered 流登记的是包装器 delegate，此前按
                // delegate 匹配导致注销失效、登记泄漏）。
                if (entry.rawSubscriber == subscriber || entry.delegate == subscriber) {
                    removed = entry;
                    entries.remove(i);
                    entry.closed = true;
                    entry.queue.clear();
                    entry.droppedByTopic.clear();
                    break;
                }
            }
            stopDispatcherIfIdleLocked();
        }
        if (removed != null) {
            try {
                removed.delegate.complete();
            } catch (RuntimeException ignored) {
                // 宿主 emitter 已失效
            }
        }
    }

    /** 打开某节点订阅视图；afterEventId 为 Last-Event-ID 游标（null = 首屏，仅补最新快照）。 */
    public PluginSseStream open(String nodeId, Long afterEventId) {
        return new ResumableStream(nodeId, afterEventId);
    }

    /**
     * 打开按事件类型+载荷键值过滤的订阅视图（实例输出/终端输出等高频流使用）：
     * 仅转发 type 匹配且 payload[matchKey]==matchValue 的事件（严格相等，缺键/缺值
     * 一律丢弃）；connected/heartbeat/stream.gap 控制事件始终放行；不补发历史。
     */
    public PluginSseStream openFiltered(String nodeId, String eventType, String matchKey, String matchValue) {
        return new FilteredStream(nodeId, eventType, matchKey, matchValue);
    }

    /** 事件监听者：在 publish 线程内同步回调，必须快速返回且不得再调用本总线。 */
    public interface Listener {
        void onEvent(String type, String nodeId, Map<String, Object> payload);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 广播节点事件并入缓存；heartbeat/connected 不入缓存。 */
    public void publish(String type, String nodeId, Map<String, Object> payload) {
        BufferedEvent event;
        List<Entry> targets;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            long id = sequence.incrementAndGet();
            event = new BufferedEvent(type, envelope(id, type, nodeId, payload));
            boolean retain = !TYPE_HEARTBEAT.equals(type) && !TYPE_CONNECTED.equals(type);
            if (retain) {
                replay.addLast(event);
                while (replay.size() > REPLAY_CAPACITY) {
                    replay.pollFirst();
                }
                // latestPerNode 仅缓存 state/stats 快照；日志/终端等高频事件不作快照。
                if (nodeId != null
                        && (TYPE_NODE_STATE.equals(type) || TYPE_NODE_STATS.equals(type))) {
                    latestPerNode.computeIfAbsent(nodeId, key -> new HashMap<>()).put(type, event);
                }
            }
            // 同锁内入队：保证并发 publish 对同一订阅者的顺序与 seq 一致（纯内存操作）。
            targets = new ArrayList<>(entries);
            for (Entry entry : targets) {
                if (!entry.closed && (entry.nodeId == null || nodeId == null || entry.nodeId.equals(nodeId))) {
                    entry.offerLocked(event);
                }
            }
        }
        // 监听者在锁外回调：回调可安全做文档落库等稍重操作，异常不得影响总线。
        for (Listener listener : listeners) {
            try {
                listener.onEvent(type, nodeId, payload == null ? Map.of() : payload);
            } catch (RuntimeException ignored) {
                // 单个监听者异常不阻断其余分发
            }
        }
    }

    public synchronized void startHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcpanel-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                if (!entries.isEmpty() && !disposed) {
                    publishLocked(TYPE_HEARTBEAT, null, Map.of());
                }
            }
        }, HEARTBEAT_PERIOD_MS, HEARTBEAT_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    /**
     * 插件卸载：置 disposed 门闸、注销并 complete 全部订阅、清缓存、
     * shutdownNow dispatcher（防 reload 线程泄漏）。之后 subscribe 立即 complete。
     */
    public void completeAll() {
        List<Entry> drained;
        synchronized (lock) {
            disposed = true;
            drained = new ArrayList<>(entries);
            entries.clear();
            for (Entry entry : drained) {
                entry.closed = true;
                entry.queue.clear();
                entry.droppedByTopic.clear();
            }
            replay.clear();
            latestPerNode.clear();
            stopDispatcherIfIdleLocked();
        }
        stopHeartbeat();
        for (Entry entry : drained) {
            try {
                entry.delegate.complete();
            } catch (RuntimeException ignored) {
                // 宿主 emitter 已失效
            }
        }
    }

    // ---------- 内部 ----------

    /** 仅 heartbeat 线程在持锁时调用（publish 需要锁，故用锁内直呼变体）。 */
    private void publishLocked(String type, String nodeId, Map<String, Object> payload) {
        long id = sequence.incrementAndGet();
        BufferedEvent event = new BufferedEvent(type, envelope(id, type, nodeId, payload));
        for (Entry entry : entries) {
            if (!entry.closed && (entry.nodeId == null || nodeId == null || entry.nodeId.equals(nodeId))) {
                entry.offerLocked(event);
            }
        }
    }

    private Map<String, Object> envelope(long id, String type, String nodeId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("at", System.currentTimeMillis());
        Map<String, Object> body = new LinkedHashMap<>();
        if (nodeId != null) {
            body.put("nodeId", nodeId);
        }
        if (payload != null) {
            body.putAll(payload);
        }
        envelope.put("payload", body);
        return envelope;
    }

    private void ensureDispatcher() {
        if (dispatcher != null && !dispatcher.isShutdown()) {
            return;
        }
        dispatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcpanel-sse-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        dispatcher.scheduleWithFixedDelay(this::drainAll, 20L, 20L, TimeUnit.MILLISECONDS);
    }

    /** 总线锁内调用：全部订阅结束后释放 dispatcher 线程（订阅取消/complete 收尾）。 */
    private void stopDispatcherIfIdleLocked() {
        if (entries.isEmpty() && dispatcher != null) {
            dispatcher.shutdownNow();
            dispatcher = null;
        }
    }

    private void drainAll() {
        List<Entry> snapshot;
        synchronized (lock) {
            if (disposed || entries.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(entries);
        }
        long now = System.currentTimeMillis();
        for (Entry entry : snapshot) {
            // 每订阅者独立预算（此前为整轮共享 deadline，慢订阅者会挤占他人预算）。
            if (!entry.drain(now + DRAIN_BUDGET_MS)) {
                unsubscribe(entry.rawSubscriber);
            }
        }
    }

    private record BufferedEvent(String type, Map<String, Object> payload) {
    }

    /**
     * 按事件类型 + 载荷键值过滤（实例输出按 instanceId、终端按 terminalId 匹配）。
     * 控制事件（connected/heartbeat/stream.gap）始终放行；数据事件严格相等匹配，
     * 缺 eventType/matchKey/matchValue 或载荷缺键一律不投递（绝不退化为直通）。
     */
    private record TypedSubscriber(String nodeId, String eventType, String matchKey, String matchValue,
                                   PluginSseStream.Subscriber delegate)
            implements PluginSseStream.Subscriber {

        @Override
        public void send(String event, Object payload) {
            if (NodeEventBus.TYPE_CONNECTED.equals(event)
                    || NodeEventBus.TYPE_HEARTBEAT.equals(event)
                    || NodeEventBus.TYPE_STREAM_GAP.equals(event)) {
                delegate.send(event, payload);
                return;
            }
            if (eventType == null || !eventType.equals(event)) {
                return;
            }
            if (matchKey == null || matchKey.isBlank() || matchValue == null || matchValue.isBlank()) {
                // 严格过滤：缺过滤键/值的数据事件一律不送出，不得静默放行全量。
                return;
            }
            if (!(payload instanceof Map<?, ?> envelope)) {
                return;
            }
            Object body = envelope.get("payload");
            if (!(body instanceof Map<?, ?> map)) {
                return;
            }
            Object actual = map.get(matchKey);
            if (actual == null || !matchValue.equals(String.valueOf(actual))) {
                return;
            }
            delegate.send(event, payload);
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        @Override
        public void error(Throwable error) {
            delegate.error(error);
        }
    }

    private final class ResumableStream implements PluginSseStream {

        private final String nodeId;
        private final Long afterEventId;

        private ResumableStream(String nodeId, Long afterEventId) {
            this.nodeId = nodeId;
            this.afterEventId = afterEventId;
        }

        @Override
        public void subscribe(PluginSseStream.Subscriber subscriber) {
            synchronized (lock) {
                if (disposed) {
                    // disable 竞态：不再接受新订阅，立即终结。
                    try {
                        subscriber.complete();
                    } catch (RuntimeException ignored) {
                        // 宿主 emitter 已失效
                    }
                    return;
                }
                ensureDispatcher();
                // 原子快照 + 登记：登记前的重放/最新快照先入队，登记后的实时事件由
                // publish 在同一锁内入队，队列 FIFO，无丢失、无交错、严格有序。
                Entry entry = new Entry(nodeId, subscriber, subscriber, null, null);
                for (BufferedEvent event : backlogFor(nodeId, afterEventId)) {
                    entry.offerLocked(event);
                }
                entry.offerLocked(new BufferedEvent(TYPE_CONNECTED,
                        envelope(sequence.get(), TYPE_CONNECTED, nodeId, Map.of(
                                "connected", true,
                                "replayFrom", afterEventId == null ? 0 : afterEventId,
                                "sequence", sequence.get()))));
                entries.add(entry);
            }
        }

        @Override
        public void unsubscribe(PluginSseStream.Subscriber subscriber) {
            NodeEventBus.this.unsubscribe(subscriber);
        }
    }

    /**
     * 锁内调用。带游标：ring 中 id > cursor 且节点匹配的事件按序；
     * 无游标：仅该节点最新 node.state 与 node.stats 各一条（快照语义）。
     */
    private List<BufferedEvent> backlogFor(String nodeId, Long afterEventId) {
        List<BufferedEvent> backlog = new ArrayList<>();
        if (afterEventId != null) {
            for (BufferedEvent event : replay) {
                if (nodeId != null) {
                    Object body = event.payload().get("payload");
                    Object eventNodeId = body instanceof Map<?, ?> map ? map.get("nodeId") : null;
                    if (!nodeId.equals(String.valueOf(eventNodeId))) {
                        continue;
                    }
                }
                Object id = event.payload().get("id");
                if (id instanceof Number number && number.longValue() <= afterEventId) {
                    continue;
                }
                backlog.add(event);
            }
            return backlog;
        }
        if (nodeId != null) {
            Map<String, BufferedEvent> latest = latestPerNode.get(nodeId);
            if (latest != null) {
                BufferedEvent state = latest.get(TYPE_NODE_STATE);
                if (state != null) {
                    backlog.add(state);
                }
                BufferedEvent stats = latest.get(TYPE_NODE_STATS);
                if (stats != null) {
                    backlog.add(stats);
                }
            }
        }
        return backlog;
    }

    /** 按事件类型与载荷键值过滤的实时流（不补历史，register 前需自行处理初始态）。 */
    private final class FilteredStream implements PluginSseStream {

        private final String nodeId;
        private final String eventType;
        private final String matchKey;
        private final String matchValue;

        private FilteredStream(String nodeId, String eventType, String matchKey, String matchValue) {
            this.nodeId = nodeId;
            this.eventType = eventType;
            this.matchKey = matchKey;
            this.matchValue = matchValue;
        }

        @Override
        public void subscribe(PluginSseStream.Subscriber subscriber) {
            synchronized (lock) {
                if (disposed) {
                    try {
                        subscriber.complete();
                    } catch (RuntimeException ignored) {
                        // 宿主 emitter 已失效
                    }
                    return;
                }
                ensureDispatcher();
                // 登记 rawSubscriber（宿主注入销毁键）+ 包装 delegate（严格过滤），
                // unsubscribe(raw) 才能正确摘除，杜绝包装器导致的登记泄漏。
                Entry entry = new Entry(nodeId, subscriber,
                        new TypedSubscriber(nodeId, eventType, matchKey, matchValue, subscriber),
                        matchKey, matchValue);
                entry.offerLocked(new BufferedEvent(TYPE_CONNECTED,
                        envelope(sequence.get(), TYPE_CONNECTED, nodeId, Map.of("connected", true))));
                entries.add(entry);
            }
        }

        @Override
        public void unsubscribe(PluginSseStream.Subscriber subscriber) {
            NodeEventBus.this.unsubscribe(subscriber);
        }
    }
}
