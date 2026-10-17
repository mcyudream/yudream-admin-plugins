package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.plugin.voxelithmap.application.dto.RenderTaskDTO;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务进度事件流（SSE）。
 *
 * <p>订阅者集合是自愈的：发送失败的订阅者当场剔除，宿主容器只负责连接超时与关闭。
 * 进度事件用节流器包一层（见 {@code RenderOrchestrator}），避免逐行输出把 SSE 打爆。</p>
 *
 * <p>事件名固定 {@code connected} / {@code task}：前端用
 * {@code sdk.http.url()} + fetch 流式读取（宿主 sa-token 只认 header，
 * {@code EventSource} 带不上令牌，不能用）。</p>
 */
public final class VoxelithEventStream implements PluginSseStream {

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    @Override
    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
        try {
            subscriber.send("connected", Map.of("connected", true));
        } catch (RuntimeException e) {
            subscribers.remove(subscriber);
        }
    }

    @Override
    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 广播一条任务进度。 */
    public void publish(RenderTaskDTO task) {
        if (task == null) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.send("task", task);
            } catch (RuntimeException e) {
                // 死连接：剔除，后续事件不再尝试
                subscribers.remove(subscriber);
            }
        }
    }

    /** 当前订阅者数量（排障/自检用）。 */
    public int subscriberCount() {
        return subscribers.size();
    }
}
