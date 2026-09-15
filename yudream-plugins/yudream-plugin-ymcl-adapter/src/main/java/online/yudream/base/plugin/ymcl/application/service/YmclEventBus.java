package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * YAP §6.10 事件总线：域内全部 SSE 订阅者共享一条总线。事件按序缓存
 * （环形，最近 {@link #REPLAY_CAPACITY} 条），新订阅者接入时先重放缓存
 * ——等价于 Last-Event-ID 续传语义的简化实现。事件只携带定位元数据，
 * 启动器收到后经 GET 拉取真实数据。
 */
public class YmclEventBus implements PluginSseStream {

    private static final int REPLAY_CAPACITY = 1000;

    public static final String TYPE_MANIFEST_UPDATED = "manifest.updated";
    public static final String TYPE_PACK_PUBLISHED = "pack.published";
    public static final String TYPE_BINDING_UPDATED = "binding.updated";
    public static final String TYPE_SESSION_CONTEXT_CHANGED = "session.context_changed";

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Deque<BufferedEvent> replay = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void subscribe(Subscriber subscriber) {
        // Replay buffered events so a reconnecting client catches up; the
        // launcher deduplicates on its side via the event sequence numbers.
        for (BufferedEvent event : replaySnapshot()) {
            subscriber.send(event.type(), event.payload());
        }
        subscribers.add(subscriber);
    }

    @Override
    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 广播事件到全部订阅者并入缓存（供后续订阅者重放）。 */
    public synchronized void publish(String type, Map<String, Object> payload) {
        long id = sequence.incrementAndGet();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("payload", payload == null ? Map.of() : payload);
        replay.addLast(new BufferedEvent(type, envelope));
        while (replay.size() > REPLAY_CAPACITY) {
            replay.pollFirst();
        }
        for (Subscriber subscriber : List.copyOf(subscribers)) {
            try {
                subscriber.send(type, envelope);
            } catch (RuntimeException ignored) {
                subscribers.remove(subscriber);
            }
        }
    }

    private List<BufferedEvent> replaySnapshot() {
        return new ArrayList<>(replay);
    }

    private record BufferedEvent(String type, Map<String, Object> payload) {
    }
}
