package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Base64;

/**
 * 实例文件上传任务（异步 + 分片流式中转）：
 * 浏览器先流式算出整文件 sha256，再按分片（建议 ~4MiB）直传面板；面板收到每片即
 * 经 file.upload.chunk 拆 96KiB 帧转发节点，不落盘全量、请求即返——上传期间页面可
 * 继续操作。任务表内存态（对齐 InstallTaskTracker：短生命周期，重启丢跟踪不丢文件），
 * 重进文件页/详情页经 view 恢复进度；浏览器发送流中断（刷新/关页）由空闲超时置失败，
 * 节点会话 10 分钟过期 + commit 强校验 size/sha256 兜底完整性。
 */
public class UploadTaskService implements AutoCloseable {

    /** 面板到节点的分块转发通道（file.upload.*；权限校验在 app 层 findAccessible）。 */
    public interface NodeUploadCall {
        Map<String, Object> call(String scopeKey, String instanceId, String method, Map<String, Object> payload);
    }

    /** 终态保留时长（供页面提示，过后从列表移除）。 */
    private static final long DONE_RETAIN_MS = 5 * 60_000L;
    /** running 任务无新分片的空闲超时（发送端刷新/关页后任务置失败）。 */
    private static final long IDLE_TIMEOUT_MS = 2 * 60_000L;
    /** 单文件上限 2GiB（节点磁盘与上传时长约束；超过应走 SFTP/节点本地导入）。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    /** 每实例并发 running 任务上限（洪泛保护）。 */
    private static final int MAX_RUNNING_PER_INSTANCE = 2;
    private static final int MAX_TRACKED = 256;
    /** 面板到节点的单帧二进制上限（协议 §4 帧 ≤256KiB，96KiB 分块与 upload() 一致）。 */
    private static final int NODE_CHUNK_BYTES = 96 * 1024;

    private final NodeUploadCall call;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    /** 任务条目（读写均经 synchronized(task) 串行化，分片顺序由 offset 校验兜底）。 */
    static final class Task {
        final String id;
        final String instanceId;
        final String scopeKey;
        final String path;
        final String name;
        final long size;
        final long startedAt;
        volatile String nodeUploadId = "";
        volatile long uploaded;
        volatile String state = "running";
        volatile String error = "";
        volatile long lastProgressAt = System.currentTimeMillis();
        volatile long finishedAt;

        Task(String id, String instanceId, String scopeKey, String path, String name,
             long size, long startedAt) {
            this.id = id;
            this.instanceId = instanceId;
            this.scopeKey = scopeKey;
            this.path = path;
            this.name = name;
            this.size = size;
            this.startedAt = startedAt;
        }
    }

    public UploadTaskService(NodeUploadCall call) {
        this.call = call;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcpanel-upload-tasks");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::sweep, 30_000L, 30_000L, TimeUnit.MILLISECONDS);
    }

    /** 开始上传：建任务并立即向节点申请上传会话（sha256 由浏览器整文件预计算）。 */
    public Map<String, Object> begin(String scopeKey, String instanceId, String path,
                                     long size, String sha256, String name) {
        if (path == null || path.isBlank()) {
            throw McpanelBusinessException.invalid("缺少上传目标路径");
        }
        if (size <= 0 || size > MAX_BYTES) {
            throw McpanelBusinessException.invalid("文件大小需在 0 与 2GiB 之间（更大的文件请走 SFTP）");
        }
        if (sha256 == null || sha256.length() != 64 || !isHex(sha256)) {
            throw McpanelBusinessException.invalid("缺少有效的整文件 sha256（64 位十六进制）");
        }
        long running = tasks.values().stream()
                .filter(task -> task.instanceId.equals(instanceId) && "running".equals(task.state))
                .count();
        if (running >= MAX_RUNNING_PER_INSTANCE) {
            throw new McpanelBusinessException("upload.busy", 429, "该实例已有上传任务在进行，请稍后再试");
        }
        Map<String, Object> begin = call.call(scopeKey, instanceId, "file.upload.begin", new LinkedHashMap<>(Map.of(
                "path", path, "size", size, "sha256", sha256.toLowerCase())));
        String uploadId = String.valueOf(begin.get("uploadId"));
        if (uploadId == null || uploadId.isBlank() || "null".equals(uploadId)) {
            throw new McpanelBusinessException("upload.begin", 502, "节点未返回 uploadId，上传已取消");
        }
        if (tasks.size() >= MAX_TRACKED) {
            sweep();
        }
        String taskId = UUID.randomUUID().toString().replace("-", "");
        Task task = new Task(taskId, instanceId, scopeKey, path,
                name == null || name.isBlank() ? fileNameOf(path) : name, size, System.currentTimeMillis());
        task.nodeUploadId = uploadId;
        tasks.put(taskId, task);
        return view(task);
    }

    /** 接收浏览器分片（顺序到达），拆 96KiB 帧流式转发节点；返回已转发字节。 */
    public Map<String, Object> chunk(String scopeKey, String taskId, long offset, byte[] data) {
        Task task = requireRunning(taskId);
        if (data == null || data.length == 0) {
            throw McpanelBusinessException.invalid("分片内容为空");
        }
        synchronized (task) {
            if (!"running".equals(task.state)) {
                throw new McpanelBusinessException("upload.not-running", 409, "上传任务已结束，分片被拒绝");
            }
            if (offset != task.uploaded) {
                throw new McpanelBusinessException("upload.offset-mismatch", 409,
                        "分片顺序错误：期望偏移 " + task.uploaded + "，收到 " + offset);
            }
            String uploadId = task.nodeUploadId;
            try {
                for (int frameOffset = 0; frameOffset < data.length; frameOffset += NODE_CHUNK_BYTES) {
                    int end = Math.min(frameOffset + NODE_CHUNK_BYTES, data.length);
                    call.call(scopeKey, task.instanceId, "file.upload.chunk", new LinkedHashMap<>(Map.of(
                            "uploadId", uploadId,
                            "offset", task.uploaded + frameOffset,
                            "content", Base64.getEncoder().encodeToString(
                                    java.util.Arrays.copyOfRange(data, frameOffset, end)))));
                }
            }
            catch (RuntimeException error) {
                fail(task, "节点转发失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                throw error;
            }
            task.uploaded += data.length;
            task.lastProgressAt = System.currentTimeMillis();
            return view(task);
        }
    }

    /** 完成上传：节点校验 size+sha256 后落盘。 */
    public Map<String, Object> commit(String scopeKey, String taskId) {
        Task task = requireRunning(taskId);
        synchronized (task) {
            if (!"running".equals(task.state)) {
                throw new McpanelBusinessException("upload.not-running", 409, "上传任务已结束");
            }
            try {
                call.call(scopeKey, task.instanceId, "file.upload.commit", new LinkedHashMap<>(Map.of(
                        "uploadId", task.nodeUploadId)));
                task.state = "done";
            }
            catch (RuntimeException error) {
                fail(task, "节点校验失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                throw error;
            }
            finally {
                task.finishedAt = System.currentTimeMillis();
            }
            return view(task);
        }
    }

    /** 取消上传：节点 abort 回收临时分片，任务置 canceled。 */
    public Map<String, Object> cancel(String scopeKey, String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw McpanelBusinessException.notFound("上传任务不存在");
        }
        synchronized (task) {
            if ("running".equals(task.state)) {
                try {
                    call.call(scopeKey, task.instanceId, "file.upload.abort", new LinkedHashMap<>(Map.of(
                            "uploadId", task.nodeUploadId)));
                }
                catch (RuntimeException ignored) {
                    // abort 失败不阻断取消：节点会话 10 分钟过期兜底清理临时分片。
                }
                task.state = "canceled";
                task.error = "已取消";
                task.finishedAt = System.currentTimeMillis();
            }
            return view(task);
        }
    }

    /** 实例的上传任务视图（running 优先 + 未过期终态），供文件页/详情页恢复显示。 */
    public List<Map<String, Object>> viewOf(String instanceId) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        tasks.values().stream()
                .filter(task -> task.instanceId.equals(instanceId))
                .filter(task -> "running".equals(task.state)
                        || now - task.finishedAt < DONE_RETAIN_MS)
                .sorted((a, b) -> Long.compare(b.startedAt, a.startedAt))
                .forEach(task -> result.add(view(task)));
        return result;
    }

    /** 实例删除时清理任务表。 */
    public void evictInstance(String instanceId) {
        tasks.values().removeIf(task -> task.instanceId.equals(instanceId));
    }

    private Map<String, Object> view(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.id);
        item.put("instanceId", task.instanceId);
        item.put("path", task.path);
        item.put("name", task.name);
        item.put("size", task.size);
        item.put("uploaded", task.uploaded);
        item.put("state", task.state);
        item.put("error", task.error);
        item.put("startedAt", task.startedAt);
        item.put("finishedAt", task.finishedAt);
        return item;
    }

    private Task requireRunning(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw McpanelBusinessException.notFound("上传任务不存在");
        }
        return task;
    }

    private void fail(Task task, String message) {
        task.state = "failed";
        task.error = message;
        task.finishedAt = System.currentTimeMillis();
        try {
            call.call(task.scopeKey, task.instanceId, "file.upload.abort", new LinkedHashMap<>(Map.of(
                    "uploadId", task.nodeUploadId)));
        }
        catch (RuntimeException ignored) {
            // 节点会话 10 分钟过期兜底清理临时分片。
        }
    }

    /** 清扫：running 空闲超时置失败（发送端刷新/关页），终态过保留期移除。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            synchronized (task) {
                if ("running".equals(task.state) && now - task.lastProgressAt > IDLE_TIMEOUT_MS) {
                    fail(task, "上传中断：发送端已停止（页面刷新或关闭）");
                }
            }
        }
        tasks.values().removeIf(task ->
                !"running".equals(task.state) && now - task.finishedAt > DONE_RETAIN_MS);
    }

    private static String fileNameOf(String path) {
        String trimmed = path.replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private static boolean isHex(String value) {
        return value.matches("[0-9a-fA-F]{64}");
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
