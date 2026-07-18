package online.yudream.plugin.worldmap.application.service;

import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.plugin.worldmap.application.dto.RenderTaskDTO;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染任务进度 SSE 事件流。
 */
public class WorldMapEventStream implements PluginSseStream {

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    @Override
    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
        subscriber.send("connected", java.util.Map.of("connected", true));
    }

    @Override
    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public void publish(RenderTaskDTO task) {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.send("task", task);
            } catch (RuntimeException ignored) {
                subscribers.remove(subscriber);
            }
        }
    }
}
