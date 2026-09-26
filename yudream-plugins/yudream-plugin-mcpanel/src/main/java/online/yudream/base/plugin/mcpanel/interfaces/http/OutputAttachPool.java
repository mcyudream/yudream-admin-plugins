package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实例输出 attach 引用计数池（浏览器面）：
 * 节点侧 instance.output.subscribe 是实例级 attach 泵（同一实例只建一个泵、多浏览器共享），
 * 因此不能由任一浏览器在页面离开时调用 unsubscribe（会把别人正在看的输出关掉）。
 *
 * 本池以每个 SSE 订阅为 owner 做引用计数：
 * - 第一个订阅者到达 → 异步 attach（节点泵开启，慢 RPC 不阻塞订阅）；
 * - 订阅者离开 → 计数递减；最后一个离开 → 异步 detach；
 * - generation 防竞态：detach 任务执行前校验期间无新订阅（gen 未变且 count 仍为 0），
 *   有新订阅则旧 detach 任务自动放弃，由新订阅触发的 attach 接管；
 * - detach 是 best-effort：节点离线/调用失败时泵残留由节点侧兜底清理
 *   （控制连接断开或实例停止时节点自行回收 attach 泵）。
 *
 * 生命周期：close() 关停异步线程；插件卸载后节点连接关闭，泵由节点侧回收。
 */
public class OutputAttachPool implements AutoCloseable {

    /** 单实例订阅引用：计数 + 防新订阅竞态的 generation。 */
    private static final class InstanceRef {

        final AtomicInteger count = new AtomicInteger();
        final AtomicLong generation = new AtomicLong();
    }

    /** 节点事件流开启器（bootstrap 注入事件总线过滤视图）。 */
    public interface BackendStreamOpener {
        PluginSseStream open(String nodeId, String instanceId);
    }

    private final McpanelInstanceAppService instances;
    private final BackendStreamOpener backend;
    private final ConcurrentHashMap<String, InstanceRef> refs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public OutputAttachPool(McpanelInstanceAppService instances, BackendStreamOpener backend) {
        this.instances = instances;
        this.backend = backend;
        this.executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "mcpanel-output-attach");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 打开按 instanceId 过滤的输出流并纳入引用计数。调用前 HTTP 层已完成数据范围校验。 */
    public PluginSseStream open(String nodeId, String instanceId) {
        PluginSseStream backendStream = backend.open(nodeId, instanceId);
        InstanceRef ref = refs.computeIfAbsent(instanceId, key -> new InstanceRef());
        return new RefStream(nodeId, instanceId, ref, backendStream);
    }

    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                System.err.println("[mcpanel] 输出 attach 池未在 1s 内退出");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        refs.clear();
    }

    // ---------- 内部 ----------

    private final class RefStream implements PluginSseStream {

        private final String nodeId;
        private final String instanceId;
        private final InstanceRef ref;
        private final PluginSseStream backendStream;

        private RefStream(String nodeId, String instanceId, InstanceRef ref, PluginSseStream backendStream) {
            this.nodeId = nodeId;
            this.instanceId = instanceId;
            this.ref = ref;
            this.backendStream = backendStream;
        }

        @Override
        public void subscribe(PluginSseStream.Subscriber subscriber) {
            backendStream.subscribe(subscriber);
            if (ref.count.incrementAndGet() == 1) {
                // 0→1：使未决 detach 任务失效（generation 前进），并异步开启节点泵。
                ref.generation.incrementAndGet();
                executor.execute(() -> attach());
            }
        }

        @Override
        public void unsubscribe(PluginSseStream.Subscriber subscriber) {
            backendStream.unsubscribe(subscriber);
            if (ref.count.decrementAndGet() <= 0) {
                ref.count.set(0);
                long captured = ref.generation.get();
                try {
                    executor.execute(() -> detachIfLast(captured));
                } catch (RejectedExecutionException error) {
                    // 池已关停/饱和：泵残留由节点侧（控制连接关闭或实例停止）兜底回收。
                    System.err.println("[mcpanel] 输出 detach 未调度（池不可用）：" + instanceId);
                }
            }
        }

        private void attach() {
            try {
                // HTTP 层已做过范围校验；此处以系统身份开启节点 attach 泵。
                instances.outputSubscribe("system", "user:system", instanceId);
            } catch (RuntimeException | LinkageError error) {
                System.err.println("[mcpanel] 输出 attach 失败（" + nodeId + "/" + instanceId + "）："
                        + error.getMessage());
            }
        }

        private void detachIfLast(long capturedGeneration) {
            try {
                if (ref.count.get() != 0 || ref.generation.get() != capturedGeneration) {
                    // 已有新订阅：放弃本次 detach，由新订阅的 attach 接管。
                    return;
                }
                instances.outputUnsubscribe("system", "user:system", instanceId);
            } catch (RuntimeException | LinkageError error) {
                System.err.println("[mcpanel] 输出 detach 失败（" + nodeId + "/" + instanceId + "）："
                        + error.getMessage());
                return;
            }
            // 仅在真正完成 detach 后移除引用；期间新订阅会 bump generation 使上面校验失败。
            if (ref.count.get() == 0 && ref.generation.get() == capturedGeneration) {
                refs.remove(instanceId, ref);
            }
        }
    }
}
