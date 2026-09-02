package online.yudream.base.plugin.mcwiki.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import online.yudream.base.plugin.spi.http.PluginSseStream;

public final class JobLogBus implements PluginSseStream {
    public record Event(long seq, String type, Map<String,Object> payload) {}
    private final String streamId;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayDeque<Event> history = new ArrayDeque<>();
    private final List<PluginSseStream.Subscriber> subscribers = new ArrayList<>();
    private boolean completed;
    public JobLogBus(String streamId) { this.streamId = streamId; }
    @Override public synchronized void subscribe(PluginSseStream.Subscriber subscriber) { history.forEach(event -> subscriber.send(event.type(), event.payload())); if (completed) subscriber.complete(); else subscribers.add(subscriber); }
    @Override public synchronized void unsubscribe(PluginSseStream.Subscriber subscriber) { subscribers.remove(subscriber); }
    public synchronized Event emit(String type, Map<String,Object> payload) { if (completed) return null; Event event = new Event(sequence.incrementAndGet(), type, payload); history.addLast(event); while (history.size() > 500) history.removeFirst(); for (PluginSseStream.Subscriber subscriber : List.copyOf(subscribers)) try { subscriber.send(type, payload); } catch (RuntimeException ex) { subscribers.remove(subscriber); } return event; }
    public synchronized void complete() { if (completed) return; completed = true; for (PluginSseStream.Subscriber subscriber : List.copyOf(subscribers)) subscriber.complete(); subscribers.clear(); }
    public synchronized List<Event> after(long seq) { return history.stream().filter(event -> event.seq() > seq).toList(); }
    public String streamId() { return streamId; }
}
