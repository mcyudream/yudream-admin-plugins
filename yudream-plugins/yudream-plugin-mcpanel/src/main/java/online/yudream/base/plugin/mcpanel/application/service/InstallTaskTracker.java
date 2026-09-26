package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 实例核心安装任务跟踪（install.run → task.get 轮询）：
 * 创建实例自动下载核心 / 安装文件后，把节点 taskId 登记为面板侧跟踪条目，
 * 后台 2s 轮询聚合进度（节点 0.3.2 起按 Content-Length 上报 0-1 进度；
 * 旧节点恒 0，展示为「进行中」无百分比），终态保留 5 分钟供页面提示后移除。
 * 仅内存态（任务短生命周期，重启丢跟踪不丢文件），实例 DTO 由 facade 装饰挂载。
 *
 * 终态兜底（避免「永远安装中、成败不可知」）：
 * 1. task.get 返回 instance.notFound（节点重启丢内存任务 / 终态 24h GC）→ 立即判失败；
 * 2. 节点连续不可达超过 MAX_POLL_FAILURES 次轮询 → 判失败；
 * 3. 进度与文件快照超过 STALL_MS 无任何变化（旧节点无停滞看门狗会永久挂起）→ 判失败。
 */
public class InstallTaskTracker implements AutoCloseable {

    public interface NodeCall {
        Map<String, Object> call(String nodeId, String method, Map<String, Object> payload);
    }

    private static final long POLL_MS = 2_000L;
    private static final long DONE_RETAIN_MS = 5 * 60_000L;
    private static final int MAX_TRACKED = 256;
    /** 连续轮询失败兜底阈值（2s × 150 ≈ 5 分钟）；包级可覆盖供测试。 */
    static int MAX_POLL_FAILURES = 150;
    /** 零进展兜底（默认 15 分钟）；包级可覆盖供测试。 */
    static long STALL_MS = 15 * 60_000L;

    private final NodeCall nodeCall;
    private final McpanelInstanceAppService.AuditRecorder audit;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 可变跟踪条目（轮询线程单写、请求线程读，volatile 保证可见性）。 */
    static final class Entry {
        final String nodeId;
        final String instanceId;
        final String taskId;
        final String fileName;
        final long startedAt;
        volatile String state = "running";
        volatile double progress;
        volatile String error = "";
        volatile long finishedAt;
        /** 节点 task.get 的 files 快照（节点 0.5.0+；旧节点无此字段保持 null）。 */
        volatile Map<String, Object> files;
        /** 最近一次进展（进度/文件快照变化）时间；零进展兜底依据。 */
        volatile long lastChangeAt;
        /** 连续轮询失败次数；成功即清零。 */
        volatile int pollFailures;
        /** 上次进展指纹（进度 + 完成/失败计数 + 在传字节数）。 */
        volatile String fingerprint = "";

        Entry(String nodeId, String instanceId, String taskId, String fileName, long startedAt) {
            this.nodeId = nodeId;
            this.instanceId = instanceId;
            this.taskId = taskId;
            this.fileName = fileName == null ? "" : fileName;
            this.startedAt = startedAt;
            this.lastChangeAt = startedAt;
        }
    }

    public InstallTaskTracker(NodeCall nodeCall, McpanelInstanceAppService.AuditRecorder audit) {
        this.nodeCall = nodeCall;
        this.audit = audit;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcpanel-install-tracker");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** install.run 已受理（节点回执 taskId）后登记；超上限丢最旧终态。 */
    public void begin(String nodeId, String instanceId, String taskId, String fileName) {
        if (nodeId == null || instanceId == null || taskId == null || taskId.isBlank()) {
            return;
        }
        if (entries.size() >= MAX_TRACKED) {
            entries.values().stream()
                    .filter(entry -> !"running".equals(entry.state))
                    .min(java.util.Comparator.comparingLong(entry -> entry.startedAt))
                    .ifPresent(entry -> entries.remove(entry.instanceId + "|" + entry.taskId));
        }
        entries.put(instanceId + "|" + taskId, new Entry(nodeId, instanceId, taskId, fileName, System.currentTimeMillis()));
    }

    /** 实例删除时清理跟踪条目。 */
    public void drop(String instanceId) {
        entries.keySet().removeIf(key -> key.startsWith(instanceId + "|"));
    }

    /** 实例是否有进行中的安装任务（重试安装的冲突检查）。 */
    public boolean hasRunning(String instanceId) {
        return entries.values().stream()
                .anyMatch(entry -> entry.instanceId.equals(instanceId) && "running".equals(entry.state));
    }

    /** 实例 DTO 装饰载荷：无活动任务返回 null。 */
    public Map<String, Object> viewOf(String instanceId) {
        List<Map<String, Object>> active = new ArrayList<>();
        entries.values().stream()
                .filter(entry -> entry.instanceId.equals(instanceId))
                .sorted(java.util.Comparator.comparingLong((Entry entry) -> entry.startedAt).reversed())
                .limit(2)
                .forEach(entry -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("fileName", entry.fileName);
                    row.put("state", entry.state);
                    row.put("progress", Math.max(0d, Math.min(1d, entry.progress)));
                    if (entry.files != null && !entry.files.isEmpty()) {
                        row.put("files", entry.files);
                    }
                    if (!entry.error.isBlank()) {
                        row.put("error", entry.error);
                    }
                    row.put("startedAt", entry.startedAt);
                    active.add(row);
                });
        return active.isEmpty() ? null : Map.of("tasks", active);
    }

    /** 单轮轮询（调度器驱动；包级可见供测试直接驱动）。 */
    void pollOnce() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            try {
                if ("running".equals(entry.state)) {
                    poll(entry);
                }
                else if (entry.finishedAt > 0
                        && System.currentTimeMillis() - entry.finishedAt > DONE_RETAIN_MS) {
                    iterator.remove();
                }
            }
            catch (RuntimeException ignored) {
                // 节点暂时不可达等：下轮重试；连续失败由实例状态兜底展示。
            }
        }
    }

    private void poll(Entry entry) {
        Map<String, Object> result;
        try {
            result = nodeCall.call(entry.nodeId, "task.get", Map.of("taskId", entry.taskId));
        }
        catch (RuntimeException error) {
            // 任务在节点上消失（节点重启丢内存任务 / 终态 24h GC）：立即判失败。
            if ("instance.notFound".equals(errorCodeOf(error))) {
                fail(entry, "节点上的安装任务已丢失（节点重启或任务过期），请重试安装");
                return;
            }
            // 节点不可达等瞬时失败：保留下轮重试；连续失败超阈值兜底判失败。
            if (++entry.pollFailures >= MAX_POLL_FAILURES) {
                fail(entry, "节点长时间不可达，安装结果未知；节点恢复后请重试安装");
            }
            return;
        }
        entry.pollFailures = 0;
        String state = String.valueOf(result.getOrDefault("state", "running"));
        Object progressRaw = result.get("progress");
        double progress = progressRaw instanceof Number number ? number.doubleValue() : 0d;
        if (progress >= 1d) {
            state = "done";
        }
        entry.state = state;
        entry.progress = Math.max(entry.progress, progress);
        Object files = result.get("files");
        if (files instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) files;
            entry.files = snapshot;
        }
        // 进展指纹：百分比或文件快照（完成/失败计数 + 在传字节数）任一变化才算活着；
        // 长时间零变化说明节点侧下载已挂死（旧节点无停滞看门狗会永久 running）。
        long now = System.currentTimeMillis();
        String fingerprint = fingerprintOf(entry);
        if (!fingerprint.equals(entry.fingerprint)) {
            entry.fingerprint = fingerprint;
            entry.lastChangeAt = now;
        }
        if (!"running".equals(state)) {
            entry.finishedAt = now;
            Object error = result.get("error");
            entry.error = error == null ? "" : String.valueOf(error);
            if (audit != null) {
                audit.record("system", "done".equals(state) ? "instance.install.done" : "instance.install.failed",
                        "instance", entry.instanceId,
                        entry.fileName + " → " + state + (entry.error.isBlank() ? "" : "：" + entry.error),
                        null);
            }
            return;
        }
        if (now - entry.lastChangeAt > STALL_MS) {
            fail(entry, "安装长时间无进展，可能已卡住；请重试安装");
        }
    }

    /** 面板兜底判负：终态保留 5 分钟展示，审计留痕。 */
    private void fail(Entry entry, String message) {
        entry.state = "failed";
        entry.error = message;
        entry.finishedAt = System.currentTimeMillis();
        if (audit != null) {
            audit.record("system", "instance.install.failed", "instance", entry.instanceId,
                    entry.fileName + " → failed：" + message, null);
        }
    }

    /** 从异常链上找节点协议错误码（装配层可能包过 CompletionException/RuntimeException）。 */
    private static String errorCodeOf(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof NodeCallException nodeCall) {
                return nodeCall.code();
            }
            cursor = cursor.getCause();
        }
        return "";
    }

    private static String fingerprintOf(Entry entry) {
        StringBuilder builder = new StringBuilder().append(entry.progress);
        Map<String, Object> files = entry.files;
        if (files != null) {
            builder.append('|').append(files.get("done")).append('|').append(files.get("failed"));
            Object active = files.get("active");
            if (active instanceof List<?> rows) {
                long bytes = 0;
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map && map.get("bytes") instanceof Number number) {
                        bytes += number.longValue();
                    }
                }
                builder.append('|').append(bytes);
            }
        }
        return builder.toString();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
