package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.ArrayList;
import java.util.Deque;
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
 * YAP §6.10 事件总线：域内全部 SSE 订阅者共享一条总线。事件按序缓存
 * （环形，最近 {@link #REPLAY_CAPACITY} 条），新订阅者接入时先重放缓存
 * ——支持 Last-Event-ID 续传（query {@code lastEventId} 或 header）。
 *
 * <p>启动器（YMCL-Axolotl）按 45s 心跳超时读流：长时间无事件时必须周期性
 * 发送 {@link #TYPE_HEARTBEAT}，否则连接会被客户端掐断并引发宿主
 * {@code AsyncRequestTimeoutException} 噪音。
 */
public class YmclEventBus implements PluginSseStream {

    private static final int REPLAY_CAPACITY = 1000;
    /** 必须短于启动器 45s 读超时（YAP §6.10 建议 30s）。 */
    private static final long HEARTBEAT_PERIOD_MS = 25_000L;

    public static final String TYPE_CONNECTED = "connected";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_MANIFEST_UPDATED = "manifest.updated";
    public static final String TYPE_PACK_PUBLISHED = "pack.published";
    public static final String TYPE_PACK_UPDATED = "pack.updated";
    public static final String TYPE_PACK_DELETED = "pack.deleted";
    public static final String TYPE_BINDING_UPDATED = "binding.updated";
    public static final String TYPE_SESSION_CONTEXT_CHANGED = "session.context_changed";

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Deque<BufferedEvent> replay = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;

    @Override
    public void subscribe(Subscriber subscriber) {
        open(null).subscribe(subscriber);
    }

    @Override
    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /**
     * 按 Last-Event-ID 打开订阅视图：{@code afterEventId == null} 时重放全部
     * 缓存；否则只重放 {@code id > afterEventId} 的事件。
     */
    public PluginSseStream open(Long afterEventId) {
        return new ResumableStream(afterEventId);
    }

    /** 广播事件到全部订阅者并入缓存（供后续订阅者重放）。心跳事件不入缓存。 */
    public synchronized void publish(String type, Map<String, Object> payload) {
        long id = sequence.incrementAndGet();
        Map<String, Object> envelope = envelope(id, type, payload);
        boolean retain = !TYPE_HEARTBEAT.equals(type) && !TYPE_CONNECTED.equals(type);
        if (retain) {
            replay.addLast(new BufferedEvent(type, envelope));
            while (replay.size() > REPLAY_CAPACITY) {
                replay.pollFirst();
            }
        }
        broadcast(type, envelope);
    }

    /** 启动心跳调度；重复调用幂等。必须在插件 disable/unload 时调用 {@link #stopHeartbeat()}。 */
    public synchronized void startHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ymcl-adapter-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    if (subscribers.isEmpty()) {
                        return;
                    }
                    try {
                        publish(TYPE_HEARTBEAT, Map.of());
                    } catch (RuntimeException ignored) {
                        // 单个订阅者写失败已在 broadcast 中移除，不影响调度
                    }
                },
                HEARTBEAT_PERIOD_MS,
                HEARTBEAT_PERIOD_MS,
                TimeUnit.MILLISECONDS);
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

    public long currentSequence() {
        return sequence.get();
    }

    private Map<String, Object> envelope(long id, String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("at", System.currentTimeMillis());
        envelope.put("payload", payload == null ? Map.of() : payload);
        return envelope;
    }

    private void broadcast(String type, Map<String, Object> envelope) {
        for (Subscriber subscriber : List.copyOf(subscribers)) {
            try {
                subscriber.send(type, envelope);
            } catch (RuntimeException ignored) {
                subscribers.remove(subscriber);
            }
        }
    }

    private List<BufferedEvent> replayAfter(Long afterEventId) {
        List<BufferedEvent> events = new ArrayList<>(replay);
        if (afterEventId == null) {
            return events;
        }
        List<BufferedEvent> filtered = new ArrayList<>();
        for (BufferedEvent event : events) {
            Object id = event.payload().get("id");
            if (id instanceof Number number && number.longValue() > afterEventId) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    private final class ResumableStream implements PluginSseStream {

        private final Long afterEventId;

        private ResumableStream(Long afterEventId) {
            this.afterEventId = afterEventId;
        }

        @Override
        public void subscribe(Subscriber subscriber) {
            // 先重放再登记：避免重放期间实时事件与缓存交错重复。
            for (BufferedEvent event : replayAfter(afterEventId)) {
                try {
                    subscriber.send(event.type(), event.payload());
                } catch (RuntimeException error) {
                    try {
                        subscriber.error(error);
                    } catch (RuntimeException ignored) {
                        // 宿主 emitter 已失效
                    }
                    return;
                }
            }
            try {
                Map<String, Object> connected = envelope(sequence.get(), TYPE_CONNECTED, Map.of(
                        "connected", true,
                        "replayFrom", afterEventId == null ? 0 : afterEventId,
                        "sequence", sequence.get()));
                subscriber.send(TYPE_CONNECTED, connected);
            } catch (RuntimeException error) {
                try {
                    subscriber.error(error);
                } catch (RuntimeException ignored) {
                    // ignore
                }
                return;
            }
            subscribers.add(subscriber);
        }

        @Override
        public void unsubscribe(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }
    }

    private record BufferedEvent(String type, Map<String, Object> payload) {
    }
}
