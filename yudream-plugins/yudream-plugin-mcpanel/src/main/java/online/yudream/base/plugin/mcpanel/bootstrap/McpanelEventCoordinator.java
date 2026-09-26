package online.yudream.base.plugin.mcpanel.bootstrap;

import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 节点事件协调器（bootstrap 装配，生命周期可关停）：把节点事件总线的同步监听
 * 拆成两条互不阻塞的通道，替代「监听者内联落库 + 无界 virtual 线程上线回读」。
 *
 * 设计要点：
 * - 总线回调（onEvent）只做入队/合并，微秒级返回，绝不阻塞 WebSocket 读线程；
 *   全程无跨调用持锁（不在锁内发起任何同步 RPC），杜绝响应事件死锁；
 * - state 快速通道：单工作线程按 FIFO 保序消费；同一 (node,instance) 的事件在
 *   槽内合并（last-write-wins，风暴时每实例最多积压一条最新状态）；队列有界，
 *   溢出丢弃并计数（下一条事件或下次上线回读兜底）；
 * - 计划任务触发以 stateSink 的来源校验结果为门：仅当事件被应用服务接受
 *  （实例存在、来源节点匹配、状态实际变化）后才入慢通道触发，跨节点伪造
 *   instanceId 的事件不会触发他人计划任务；触发在 state 通道内产生，天然保序；
 * - 慢通道：计划任务命令下发与上线回读（instance.list + 状态同步是同步 RPC）
 *   独立单线程执行，与 state 通道、WebSocket、新 state 事件完全隔离；
 * - 上线回读按节点去重：同一节点的回读在队/在跑时忽略后续 online 事件，
 *   回读执行时读取节点实时状态（过期会话数据天然失效），落库再经仓储 CAS
 *   防删后复活与旧读覆盖，构成三层过期隔离；
 * - 槽位注册表做惰性空闲清扫（长空闲且无在队任务的槽被移除，随用随建），
 *   注册表不随实例生命周期无界累计；容量上限仅作洪泛保护；
 * - close() 关停两条通道并拒绝后续事件（disposed 门闸），供 onDispose 注册。
 */
public class McpanelEventCoordinator implements NodeEventBus.Listener {

    /**
     * 状态事件消费者：(sourceNodeId, payload) → app 层 onNodeInstanceEvent。
     * 返回 true = 事件通过来源校验且状态实际变化（app 已落库）；false = 拒绝/无变化。
     */
    public interface StateSink {
        boolean accept(String sourceNodeId, Map<String, Object> payload);
    }

    /** 节点离线消费者：app 层 onNodeOffline(nodeId)。 */
    public interface NodeOfflineSink extends Consumer<String> {
    }

    /** 上线回读任务：app 层 syncStatesFromNode(nodeId)。 */
    public interface SyncBackfill {
        int sync(String nodeId);
    }

    /** stats 快照纠偏：app 层 onNodeStatsSnapshot(nodeId, stats)。 */
    public interface StatsReconciler {
        int reconcile(String nodeId, Map<String, Object> stats);
    }

    /** 计划任务事件触发：ScheduleService.onInstanceEvent(instanceId, eventName)。 */
    public interface ScheduleTrigger {
        void trigger(String instanceId, String eventName);
    }

    /** state 槽位上限（防未知 instanceId 洪泛；正常空闲槽会被清扫回收）。 */
    private static final int MAX_STATE_SLOTS = 4096;
    /** 触发槽位上限。 */
    private static final int MAX_TRIGGER_SLOTS = 512;
    /** stats 纠偏槽位上限（key = nodeId，节点数远小于实例数）。 */
    private static final int MAX_STATS_SLOTS = 256;
    /** 各通道队列容量（每个活跃槽最多占 1 个任务位）。 */
    private static final int STATE_QUEUE_CAPACITY = 2048;
    private static final int SLOW_QUEUE_CAPACITY = 512;
    /** 空闲槽清扫：无在队任务且最新载荷为空、超过该时长的槽从注册表移除。 */
    static final long IDLE_SLOT_MS = 10 * 60 * 1000L;
    /** 每 N 次 offer 做一次摊销清扫。 */
    private static final int SWEEP_EVERY = 512;

    private final StateSink stateSink;
    private final NodeOfflineSink offlineSink;
    private final SyncBackfill syncBackfill;
    private final StatsReconciler statsReconciler;
    private final ScheduleTrigger scheduleTrigger;

    private final ThreadPoolExecutor stateLane;
    private final ThreadPoolExecutor slowLane;
    /** key = nodeId|instanceId → 槽（槽内保留最新 payload）。 */
    private final ConcurrentHashMap<String, Slot> stateSlots = new ConcurrentHashMap<>();
    /** key = nodeId|instanceId|eventName → 槽（合并重复触发）。 */
    private final ConcurrentHashMap<String, Slot> triggerSlots = new ConcurrentHashMap<>();
    /** key = nodeId → 槽（槽内保留最新 stats 快照，5s 级合并）。 */
    private final ConcurrentHashMap<String, Slot> statsSlots = new ConcurrentHashMap<>();
    /** 在队/在跑的上线回读节点（去重）。 */
    private final Set<String> syncInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong droppedStates = new AtomicLong();
    private final AtomicLong droppedTriggers = new AtomicLong();
    private final AtomicLong offerCount = new AtomicLong();
    private volatile boolean disposed;

    public McpanelEventCoordinator(StateSink stateSink,
                                   NodeOfflineSink offlineSink,
                                   SyncBackfill syncBackfill,
                                   StatsReconciler statsReconciler,
                                   ScheduleTrigger scheduleTrigger) {
        this.stateSink = stateSink;
        this.offlineSink = offlineSink;
        this.syncBackfill = syncBackfill;
        this.statsReconciler = statsReconciler;
        this.scheduleTrigger = scheduleTrigger;
        this.stateLane = lane("mcpanel-evt-state", STATE_QUEUE_CAPACITY);
        this.slowLane = lane("mcpanel-evt-slow", SLOW_QUEUE_CAPACITY);
    }

    private static ThreadPoolExecutor lane(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    // ---------- 总线入口（快速返回，不做任何阻塞调用） ----------

    @Override
    public void onEvent(String type, String nodeId, Map<String, Object> payload) {
        if (disposed || type == null) {
            return;
        }
        switch (type) {
            case "instance.state" -> {
                if (payload == null) {
                    return;
                }
                String instanceId = text(payload.get("instanceId"));
                if (instanceId == null) {
                    return;
                }
                // 快速通道：state 落库（槽内合并，保序）；计划任务触发在 state 消费、
                // 来源校验通过之后才入慢通道（见 consumeState）。
                final String sourceNodeId = nodeId;
                offer(stateSlots, MAX_STATE_SLOTS, stateLane, droppedStates,
                        nodeId + "|" + instanceId, payload,
                        event -> consumeState(sourceNodeId, event));
            }
            case NodeEventBus.TYPE_NODE_STATE -> {
                String status = text(payload.get("status"));
                if ("offline".equals(status)) {
                    // 离线标记走 state 通道保序：先处理完该节点已排队的状态事件再置 unknown。
                    if (!disposed && !stateLane.isShutdown()) {
                        try {
                            stateLane.execute(() -> {
                                try {
                                    offlineSink.accept(nodeId);
                                } catch (RuntimeException | LinkageError ignored) {
                                    // 单次离线标记失败不影响通道
                                }
                            });
                        } catch (RejectedExecutionException ignored) {
                            // 通道已关停（close 竞态）：插件卸载中，忽略
                        }
                    }
                } else if ("online".equals(status) && nodeId != null) {
                    // 慢通道：上线回读按节点去重（在队/在跑即忽略），执行时读节点实时状态。
                    if (syncInFlight.add(nodeId)) {
                        try {
                            slowLane.execute(() -> {
                                try {
                                    syncBackfill.sync(nodeId);
                                } catch (RuntimeException | LinkageError ignored) {
                                    // 回读失败不阻塞事件总线；下次上线或手动操作仍会触发。
                                } finally {
                                    syncInFlight.remove(nodeId);
                                }
                            });
                        } catch (RejectedExecutionException error) {
                            syncInFlight.remove(nodeId);
                            droppedTriggers.incrementAndGet();
                        }
                    }
                }
            }
            case NodeEventBus.TYPE_NODE_STATS -> {
                // stats 快照纠偏：走 state 通道与 instance.state 落库串行（同实例写入保序），
                // 槽内按节点合并（5s 级上报，无需逐条消费）。只纠偏落库，不触发计划任务。
                if (nodeId != null && payload != null && !disposed) {
                    final String sourceNodeId = nodeId;
                    offer(statsSlots, MAX_STATS_SLOTS, stateLane, droppedStates,
                            nodeId, payload, event -> consumeStats(sourceNodeId, event));
                }
            }
            default -> {
                // instance.output / node.terminal.output / ftp.* 等高频流：
                // 由 SSE 订阅者直连消费，协调器不处理。
            }
        }
    }

    /**
     * state 消费（state 通道内、保序执行）：先经 app 层来源校验并落库；
     * 仅在事件被接受（实例存在、来源匹配、状态实际变化）后才映射计划任务事件
     * 并入慢通道触发——伪造来源/他节点 instanceId 的事件不会触发他人计划。
     */
    private void consumeState(String sourceNodeId, Map<String, Object> payload) {
        boolean accepted;
        try {
            accepted = stateSink.accept(sourceNodeId, payload);
        } catch (RuntimeException | LinkageError error) {
            accepted = false;
        }
        if (!accepted) {
            return;
        }
        String eventName = scheduleEventName(payload.get("state"));
        String instanceId = text(payload.get("instanceId"));
        if (eventName == null || instanceId == null) {
            return;
        }
        offer(triggerSlots, MAX_TRIGGER_SLOTS, slowLane, droppedTriggers,
                sourceNodeId + "|" + instanceId + "|" + eventName,
                Map.of("instanceId", instanceId, "event", eventName),
                event -> scheduleTrigger.trigger(
                        text(event.get("instanceId")), text(event.get("event"))));
    }

    /** stats 快照消费（state 通道内，与 instance.state 落库同通道保序）：差异纠偏落库。 */
    private void consumeStats(String sourceNodeId, Map<String, Object> payload) {
        try {
            statsReconciler.reconcile(sourceNodeId, payload);
        } catch (RuntimeException | LinkageError ignored) {
            // 纠偏失败不影响通道；下一条快照（5s 级）自然重试。
        }
    }

    /**
     * 槽式合并入队：新事件写入槽（last-write-wins）；槽未被调度时投递一个消费任务。
     * scheduled 标志保证每个槽同时至多 1 个在队/在跑任务（无删槽竞态、无重复消费）。
     * 摊销清扫：注册表中的空闲槽会被移除（随用随建），容量上限仅作洪泛保护。
     */
    private void offer(ConcurrentHashMap<String, Slot> slots, int cap, ThreadPoolExecutor lane,
                       AtomicLong dropped, String key, Map<String, Object> payload,
                       Consumer<Map<String, Object>> handler) {
        maybeSweep(slots);
        Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() >= cap) {
                // 槽位洪泛保护：超限时不再新开槽（既有槽仍可继续合并）。
                dropped.incrementAndGet();
                return;
            }
            slot = slots.computeIfAbsent(key, k -> new Slot());
        }
        slot.lastUsedAtMs = System.currentTimeMillis();
        slot.latest.set(payload);
        final Slot chosen = slot;
        if (chosen.scheduled.compareAndSet(false, true)) {
            try {
                lane.execute(() -> drain(lane, chosen, handler));
            } catch (RejectedExecutionException error) {
                chosen.scheduled.set(false);
                dropped.incrementAndGet();
            }
        }
    }

    /**
     * 通道工作循环：取出槽内最新载荷直到为空；finally 释放 scheduled 后兜底补投，
     * 覆盖「释放标志与新事件入槽」竞态间隙，事件不丢、不重（每个槽同时至多一个任务）。
     * 槽不回查注册表：清扫移除孤儿槽不影响在途载荷投递，后续 offer 随用随建新槽。
     */
    private void drain(ThreadPoolExecutor lane, Slot slot, Consumer<Map<String, Object>> handler) {
        try {
            Map<String, Object> payload;
            while (!disposed && (payload = slot.latest.getAndSet(null)) != null) {
                try {
                    handler.accept(payload);
                } catch (RuntimeException | LinkageError ignored) {
                    // 单条事件失败不终止通道
                }
            }
        } finally {
            slot.scheduled.set(false);
            // 兜底：释放标志后槽内若仍有新落载荷，补投一轮（载荷留在槽内由下一轮取出，
            // 不在此处取走，避免取走后 reschedule 读到空槽而丢事件）。
            if (!disposed && slot.latest.get() != null && slot.scheduled.compareAndSet(false, true)) {
                try {
                    lane.execute(() -> drain(lane, slot, handler));
                } catch (RejectedExecutionException error) {
                    slot.scheduled.set(false);
                }
            }
        }
    }

    // ---------- 生命周期 ----------

    /** 关停：置 disposed 门闸、中断两条通道（有界等待）、清空全部槽位。幂等。 */
    public void close() {
        disposed = true;
        stateSlots.clear();
        triggerSlots.clear();
        syncInFlight.clear();
        shutdown(stateLane, "mcpanel-evt-state");
        shutdown(slowLane, "mcpanel-evt-slow");
    }

    public boolean isDisposed() {
        return disposed;
    }

    public long droppedStateEvents() {
        return droppedStates.get();
    }

    public long droppedTriggers() {
        return droppedTriggers.get();
    }

    /**
     * 立即清扫两个注册表：移除空闲超过 idleMs、无在队任务且无待处理载荷的槽。
     * 包可见供回归测试使用；返回移除的槽数。
     */
    int sweepIdleSlots(long idleMs) {
        long now = System.currentTimeMillis();
        return sweep(stateSlots, idleMs, now) + sweep(triggerSlots, idleMs, now);
    }

    // ---------- 内部 ----------

    private void maybeSweep(ConcurrentHashMap<String, Slot> slots) {
        if ((offerCount.incrementAndGet() & (SWEEP_EVERY - 1)) == 0) {
            sweep(slots, IDLE_SLOT_MS, System.currentTimeMillis());
        }
    }

    private static int sweep(ConcurrentHashMap<String, Slot> slots, long idleMs, long now) {
        int removed = 0;
        Iterator<Map.Entry<String, Slot>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Slot> entry = iterator.next();
            Slot slot = entry.getValue();
            // 双条件缺一不可：无在队任务且槽内无待处理载荷。即使与并发 offer 竞态，
            // 被移除的孤儿槽仍能完成本次投递（drain 直引槽对象，不回查注册表），
            // 后续 offer 随用随建新槽——只影响合并，不丢事件。
            if (now - slot.lastUsedAtMs >= idleMs
                    && !slot.scheduled.get()
                    && slot.latest.get() == null) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static void shutdown(ThreadPoolExecutor lane, String name) {
        lane.shutdownNow();
        try {
            if (!lane.awaitTermination(1, TimeUnit.SECONDS)) {
                System.err.println("[mcpanel] 事件通道未在 1s 内退出：" + name);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /** evt instance.state → 计划任务事件名（running→start、exited→exit，其余不触发）。 */
    private static String scheduleEventName(Object state) {
        if (state == null) {
            return null;
        }
        return switch (String.valueOf(state)) {
            case "running" -> "instance.start";
            case "exited" -> "instance.exit";
            default -> null;
        };
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** (node,instance)/(node,instance,event) 的合并槽：最新载荷 + 在队标志 + 最近使用时间。 */
    private static final class Slot {
        final AtomicReference<Map<String, Object>> latest = new AtomicReference<>();
        final AtomicBoolean scheduled = new AtomicBoolean(false);
        volatile long lastUsedAtMs = System.currentTimeMillis();
    }
}
