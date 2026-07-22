package online.yudream.plugin.webcard.application;

import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class AgentMessageStream implements PluginSseStream {
    private record Event(String name, Object payload) { }

    private final String streamId;
    private final String sessionId;
    private final Consumer<AgentMessageStream> task;
    private final Executor executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final List<Event> events = new ArrayList<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private volatile boolean completed;

    AgentMessageStream(String streamId, String sessionId, Consumer<AgentMessageStream> task) {
        this(streamId, sessionId, task, Runnable::run);
    }

    AgentMessageStream(String streamId, String sessionId, Consumer<AgentMessageStream> task, Executor executor) {
        this.streamId = streamId;
        this.sessionId = sessionId;
        this.task = task;
        this.executor = executor;
    }

    @Override
    public void subscribe(Subscriber subscriber) {
        synchronized (this) {
            for (Event event : events) subscriber.send(event.name(), event.payload());
            if (completed) {
                subscriber.complete();
                return;
            }
            subscribers.add(subscriber);
        }
        if (started.compareAndSet(false, true)) executor.execute(() -> {
            try {
                task.accept(this);
            } catch (Throwable error) {
                fail(error);
            }
        });
    }

    @Override
    public synchronized void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    void start() { publish("message.start", payload()); }
    void delta(String delta) { publish("message.delta", payload("delta", delta)); }
    void completeMessage(String content) { publish("message.complete", payload("content", content)); }
    void proposal(Object proposal) { publish("proposal.ready", payload("proposal", proposal)); }
    void warning(String message) { publish("proposal.warning", payload("message", message)); }

    synchronized void complete() {
        if (completed) return;
        completed = true;
        for (Subscriber subscriber : List.copyOf(subscribers)) subscriber.complete();
        subscribers.clear();
    }

    synchronized boolean isCompleted() { return completed; }
    String sessionId() { return sessionId; }

    private void fail(Throwable error) {
        publish("message.error", payload("message", safeMessage(error)));
        synchronized (this) {
            if (completed) return;
            completed = true;
            for (Subscriber subscriber : List.copyOf(subscribers)) subscriber.complete();
            subscribers.clear();
        }
    }

    private synchronized void publish(String name, Object value) {
        if (completed) return;
        Event event = new Event(name, value);
        events.add(event);
        for (Subscriber subscriber : List.copyOf(subscribers)) {
            try {
                subscriber.send(name, value);
            } catch (RuntimeException ignored) {
                subscribers.remove(subscriber);
            }
        }
    }

    private Map<String, Object> payload() {
        return Map.of("streamId", streamId, "sessionId", sessionId);
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(payload());
        result.put(key, value);
        return result;
    }

    private String safeMessage(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        String message = value.getMessage();
        return message == null || message.isBlank() ? value.getClass().getSimpleName() : message;
    }
}
