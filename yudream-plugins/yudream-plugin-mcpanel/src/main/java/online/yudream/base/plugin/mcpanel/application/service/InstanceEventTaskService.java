package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 事件触发型任务（对标 MCSM instance.eventTask，面板侧实现，语义不下发节点）：
 *
 * <ul>
 *   <li><b>自动重启</b>（autoRestart）：实例状态事件迁移到 exited（容器 die，未经任何
 *       stop API）且非面板操作所致时，立刻发起一次启动。面板发起的停止/重启/强杀/删除、
 *       控制台/计划任务下发的 stop 命令、宿主机直接 docker stop/restart（docker 会额外
 *       产生 stop 动作 → stopped 事件，不在触发词内）都不会触发；</li>
 *   <li><b>自动启动</b>（autoStart）：节点上线（节点进程重启、节点机开机、面板重连）完成
 *       状态回读后，对本节点上未运行的实例发起一次启动——配合节点开机自启即「开机自启实例」。</li>
 * </ul>
 *
 * <p>线程模型：实例状态事件在协调器 state 快速通道内落库，本服务钩子只入队（微秒级），
 * 启动 RPC 在自有单线程内串行执行，不阻塞状态通道与计划任务慢通道；节点上线触发由
 * bootstrap 包装的上线回读任务链式调用，同样只入队。面板重启后内存意图/熔断状态清零
 * （手动停止的抑制窗口远短于事件链路时延即可生效；持久化这些瞬态没有价值）。
 *
 * <p>防风暴：同实例触发节流；「自动拉起后 60s 内又崩」计一次快速崩溃，连续 3 次熔断
 * 自动重启（审计可见），手动启动一次即恢复——避免坏命令/坏镜像造成 start 风暴。
 */
public class InstanceEventTaskService {

    /** 启动 RPC 超时：与实例服务 action 的默认节点调用超时同一口径。 */
    private static final long CALL_TIMEOUT_MS = 45_000L;
    /** 手动停止意图窗口：面板 stop/restart/kill 或控制台 stop 后该窗口内的 exited 视为手动结果。 */
    private static final long MANUAL_STOP_WINDOW_MS = 90_000L;
    /** 同实例两次自动触发之间的最小间隔（事件重放/快速连崩时压 RPC 频率）。 */
    private static final long TRIGGER_THROTTLE_MS = 10_000L;
    /** 自动拉起后在该时长内再次退出视为「快速崩溃」。 */
    private static final long FAST_CRASH_MS = 60_000L;
    /** 连续快速崩溃达到该次数即熔断自动重启，手动启动一次恢复。 */
    private static final int FAST_CRASH_LIMIT = 3;

    /** 自动重启只认 exited（容器 die）：stopped=有 stop API 介入（手动语义），不触发。 */
    private static final Set<String> RESTARTABLE_STATES = Set.of("exited");
    /** 自动启动允许拉起的状态：节点回读后仍未运行的实例（unknown = 回读缺失时的兜底尝试）。 */
    private static final Set<String> AUTO_STARTABLE_STATES = Set.of("exited", "created", "unknown");

    /** 面板侧自动动作的审计主体（与节点级联清理同一约定）。 */
    private static final String SYSTEM_ACTOR = "system";

    /** 节点启动调用端口：与实例服务同一 NodeCallGateway 形状，bootstrap 注入同一实现。 */
    public interface StartGateway {
        CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                    Map<String, Object> payload, long timeoutMs);
    }

    private final McpanelInstanceRepository instances;
    private final StartGateway gateway;
    /** 自动动作审计：复用实例服务的审计端口（bootstrap 传同一实现）。 */
    private final McpanelInstanceAppService.AuditRecorder audit;
    /** 同实例两次自动触发之间的最小间隔（生产常量；测试可注入更小值）。 */
    private final long triggerThrottleMs;

    /** instanceId → 最近一次面板手动停止/重启/强杀/删除标记（毫秒）。 */
    private final ConcurrentHashMap<String, Long> manualStopAt = new ConcurrentHashMap<>();
    /** instanceId → 最近一次自动（事件任务）启动时间，快速崩溃判定基线。 */
    private final ConcurrentHashMap<String, Long> autoStartedAt = new ConcurrentHashMap<>();
    /** instanceId → 最近一次自动触发尝试（节流）。 */
    private final ConcurrentHashMap<String, Long> lastTriggerAt = new ConcurrentHashMap<>();
    /** instanceId → 连续快速崩溃计数。 */
    private final ConcurrentHashMap<String, Integer> fastCrashes = new ConcurrentHashMap<>();
    /** 熔断中的实例：自动重启暂停，手动启动一次即恢复。 */
    private final Set<String> suspended = ConcurrentHashMap.newKeySet();

    private final ThreadPoolExecutor executor;
    private volatile boolean closed;

    public InstanceEventTaskService(McpanelInstanceRepository instances, StartGateway gateway,
                                    McpanelInstanceAppService.AuditRecorder audit) {
        this(instances, gateway, audit, TRIGGER_THROTTLE_MS);
    }

    /** 包内测试构造：注入更小的触发节流，避免秒级等待。 */
    InstanceEventTaskService(McpanelInstanceRepository instances, StartGateway gateway,
                             McpanelInstanceAppService.AuditRecorder audit, long triggerThrottleMs) {
        this.instances = instances;
        this.gateway = gateway;
        this.audit = audit;
        this.triggerThrottleMs = triggerThrottleMs;
        this.executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "mcpanel-event-task");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    // ---------- 面板操作意图（由实例应用服务在发起动作前调用） ----------

    /** 面板发起 stop/restart/kill、删除实例或控制台/计划任务下发 stop 时标记：下一个窗口内的退出不自动重启。 */
    public void markManualStop(String instanceId) {
        if (instanceId != null && !instanceId.isBlank()) {
            manualStopAt.put(instanceId, System.currentTimeMillis());
        }
    }

    /** 面板（或用户）手动启动成功后调用：复位快速崩溃熔断与计数（对标 MCSM 手动 open 清零计数）。 */
    public void onManualStart(String instanceId) {
        if (instanceId == null) {
            return;
        }
        suspended.remove(instanceId);
        fastCrashes.remove(instanceId);
        autoStartedAt.remove(instanceId);
    }

    // ---------- 事件触发入口 ----------

    /**
     * 实例状态事件迁移到 exited 后调用（仅在事件通过来源校验且实际落库时）：
     * autoRestart 开启且退出非面板操作所致 → 自动拉起。只入队，不阻塞事件通道。
     */
    public void onInstanceExited(String instanceId) {
        if (instanceId == null || instanceId.isBlank() || closed) {
            return;
        }
        submit(() -> restartUnexpectedly(instanceId, System.currentTimeMillis()));
    }

    /**
     * 节点上线（状态回读完成后由 bootstrap 链式调用）：对本节点 autoStart 开启且未运行的
     * 实例各发起一次启动。只入队，回读慢通道立即返回。
     *
     * @return 入队即返回 0（保持 SyncBackfill 返回值语义：状态变更数由回读自己统计）
     */
    public int onNodeOnline(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || closed) {
            return 0;
        }
        submit(() -> autoStartForNode(nodeId));
        return 0;
    }

    /** 插件卸载时关停触发线程（bootstrap onDispose 注册）。幂等。 */
    public void close() {
        closed = true;
        executor.shutdownNow();
        manualStopAt.clear();
        autoStartedAt.clear();
        lastTriggerAt.clear();
        fastCrashes.clear();
        suspended.clear();
    }

    // ---------- 内部：自动重启 ----------

    private void restartUnexpectedly(String instanceId, long now) {
        if (suspended.contains(instanceId)) {
            return;
        }
        McpanelInstance instance = instances.findById(instanceId).orElse(null);
        if (instance == null || !instance.autoRestart()) {
            return;
        }
        String state = String.valueOf(instance.state()).toLowerCase(Locale.ROOT);
        if (!RESTARTABLE_STATES.contains(state)) {
            // 已被其他事件/操作拉起或处于非可启动态：不重复触发。
            return;
        }
        Long manualAt = manualStopAt.remove(instanceId);
        if (manualAt != null && now - manualAt <= MANUAL_STOP_WINDOW_MS) {
            // 手动停止（或重启的停止相）：本次退出不自动重启（consume-once，对标 MCSM ignore）。
            return;
        }
        Long last = lastTriggerAt.get(instanceId);
        if (last != null && now - last < triggerThrottleMs) {
            return;
        }
        Long startedAt = autoStartedAt.get(instanceId);
        if (startedAt != null && now - startedAt < FAST_CRASH_MS) {
            int crashes = fastCrashes.merge(instanceId, 1, Integer::sum);
            if (crashes >= FAST_CRASH_LIMIT) {
                suspended.add(instanceId);
                audit.record(SYSTEM_ACTOR, "instance.event-task.suspend", "instance", instanceId,
                        instance.name() + "：自动拉起后连续 " + crashes + " 次快速崩溃（<60s），"
                                + "已熔断自动重启；手动启动一次后自动恢复", instance.tenantId());
                return;
            }
        }
        else {
            // 上一次自动拉起的运行已超过快速崩溃窗口：连续崩溃计数归零。
            fastCrashes.remove(instanceId);
        }
        lastTriggerAt.put(instanceId, now);
        startInstance(instance, "instance.event-task.restart",
                "事件任务自动重启：实例未经面板操作退出，已自动拉起");
    }

    // ---------- 内部：节点上线自动启动 ----------

    private void autoStartForNode(String nodeId) {
        List<McpanelInstance> nodeInstances = instances.findAll().stream()
                .filter(instance -> nodeId.equals(instance.nodeId()) && instance.autoStart())
                .toList();
        for (McpanelInstance instance : nodeInstances) {
            if (closed) {
                return;
            }
            String instanceId = instance.id();
            String state = String.valueOf(instance.state()).toLowerCase(Locale.ROOT);
            if (!AUTO_STARTABLE_STATES.contains(state)) {
                continue;
            }
            long now = System.currentTimeMillis();
            Long last = lastTriggerAt.get(instanceId);
            if (last != null && now - last < triggerThrottleMs) {
                continue;
            }
            lastTriggerAt.put(instanceId, now);
            startInstance(instance, "instance.event-task.start",
                    "事件任务自动启动：节点上线，实例未运行，已自动发起一次启动");
        }
    }

    // ---------- 内部：启动下发与状态回写 ----------

    private void startInstance(McpanelInstance instance, String auditAction, String detail) {
        String instanceId = instance.id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instanceId);
        payload.put("ik", "panel-eventtask-" + instanceId + "-" + auditAction + "-" + System.currentTimeMillis());
        autoStartedAt.put(instanceId, System.currentTimeMillis());
        try {
            Map<String, Object> result = gateway
                    .call(instance.nodeId(), "instance.start", payload, CALL_TIMEOUT_MS)
                    .get(CALL_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS);
            String targetState = result.get("state") == null
                    ? "running"
                    : String.valueOf(result.get("state"));
            instances.mutateState(instanceId, current ->
                    current.withState(targetState, current.lastExitCode(), System.currentTimeMillis()));
            audit.record(SYSTEM_ACTOR, auditAction, "instance", instanceId,
                    instance.name() + "：" + detail, instance.tenantId());
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            auditFailure(instance, auditAction, "自动启动被中断");
        }
        catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            auditFailure(instance, auditAction, "自动启动失败：" + message);
        }
    }

    private void auditFailure(McpanelInstance instance, String auditAction, String message) {
        try {
            audit.record(SYSTEM_ACTOR, auditAction + ".failed", "instance", instance.id(),
                    instance.name() + "：" + message, instance.tenantId());
        }
        catch (RuntimeException ignored) {
            // 审计通道故障不再传播（触发线程内）
        }
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                }
                catch (RuntimeException | LinkageError ignored) {
                    // 单次触发失败不影响后续事件
                }
            });
        }
        catch (RejectedExecutionException ignored) {
            // 已 close 或队列溢出（极端风暴）：丢弃本次，后续事件/上线兜底
        }
    }
}
