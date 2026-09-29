package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实例统一事件流：把多个作用域的事件源合并为一条 SSE。
 *
 * <p>帧原样转发（事件名保留：instance.output / instance.state / node.stats），
 * 前端按事件名分发，解析逻辑与多流时代完全一致。自带 25s 心跳（帧仅用于保活，
 * 前端空闲超时永不误判）；任一源结束/出错不终结整流（节点瞬时断连不应杀掉
 * 控制台），该源的数据恢复由前端随 10s 状态刷新周期对整流重连兜底。
 *
 * <p>浏览器断开（unsubscribe）时整体拆除：逐源退订——输出源经 attach 池
 * 引用计数递减，事件视图由事件总线自行回收。
 */
final class InstanceEventStreamMux implements PluginSseStream {

    private static final long HEARTBEAT_MILLIS = 25L * 1000;

    private final List<PluginSseStream> sources;
    private final List<PluginSseStream.Subscriber> adapters = new ArrayList<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicLong heartbeatId = new AtomicLong();
    private volatile PluginSseStream.Subscriber subscriber;
    private volatile java.util.concurrent.ScheduledExecutorService heartbeatExecutor;

    private InstanceEventStreamMux(PluginSseStream... sources) {
        this.sources = List.of(sources);
    }

    static InstanceEventStreamMux merge(PluginSseStream... sources) {
        return new InstanceEventStreamMux(sources);
    }

    @Override
    public void subscribe(PluginSseStream.Subscriber subscriber) {
        this.subscriber = subscriber;
        for (PluginSseStream source : sources) {
            PluginSseStream.Subscriber adapter = new PluginSseStream.Subscriber() {
                @Override
                public void send(String event, Object data) {
                    PluginSseStream.Subscriber target = subscriber;
                    if (target != null) {
                        target.send(event, data);
                    }
                }

                @Override
                public void complete() {
                    // 单源结束（如输出泵随容器退出）不终结整流：其余作用域继续服务；
                    // 该源恢复后由前端随状态刷新周期重连整流兜底。
                }

                @Override
                public void error(Throwable throwable) {
                    // 同上：单源错误不终结整流。
                }
            };
            adapters.add(adapter);
            source.subscribe(adapter);
        }
        startHeartbeat();
    }

    private void startHeartbeat() {
        java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcpanel-instance-events-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;
        executor.scheduleWithFixedDelay(() -> {
            PluginSseStream.Subscriber target = subscriber;
            if (target == null) {
                return;
            }
            try {
                target.send("heartbeat", Map.of("id", heartbeatId.incrementAndGet(), "at", System.currentTimeMillis()));
            } catch (RuntimeException ignored) {
                // 心跳失败不终结整流：连接级存活由浏览器/边缘负责。
            }
        }, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void unsubscribe(PluginSseStream.Subscriber subscriber) {
        finish();
    }

    /** 浏览器断开/路由离开：停心跳、逐源退订（输出源退订即 attach 引用计数递减）。 */
    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        subscriber = null;
        java.util.concurrent.ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        for (int i = 0; i < sources.size(); i++) {
            try {
                sources.get(i).unsubscribe(adapters.get(i));
            } catch (RuntimeException ignored) {
                // 单源退订失败不阻塞其余清理。
            }
        }
    }
}
