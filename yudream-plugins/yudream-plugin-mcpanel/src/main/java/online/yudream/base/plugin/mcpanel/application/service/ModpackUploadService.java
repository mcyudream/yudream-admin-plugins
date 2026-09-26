package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 整合包分片上传暂存（实例创建场景：实例尚不存在，不能走实例文件分片通道）。
 *
 * <p>背景：旧流程把整个 zip（≤64MB）作为单个 multipart 传给 /admin/modpacks/inspect，
 * 宿主会把 multipart 部件全量载入堆且大包传输易撞请求超时。改为浏览器流式 sha256 +
 * 4MiB 分片直传面板内存缓冲，commit 时校验 size+sha256 后同步解析（复用
 * {@link ModpackService#inspect(byte[])}，CF manifest 外呼仍可能耗时数十秒），
 * 返回与旧 inspect 完全相同的摘要（token + 识别信息），下游 modpack-apply 不变。
 *
 * <p>内存边界：单任务上限 64MB、并发 running 任务全局上限 2、任务表上限 32；
 * 缓冲在终态（done/failed/canceled）立即释放，记录短暂保留供响应丢失后查询；
 * 30s 周期清扫：running 空闲超时置失败、终态过保留期移除。任务表内存态，
 * 面板重启即清空（上传中断后重新选择文件即可，无需恢复）。
 */
public class ModpackUploadService implements AutoCloseable {

    /** 与 ModpackService.MAX_ZIP_BYTES 对齐：超过 inspect 本来就会拒绝，begin 即拦截。 */
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    /** 建议分片 4MiB（与实例文件分片同一标准），单分片上限 8MiB。 */
    static final long SUGGESTED_CHUNK_BYTES = 4L * 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    /** 并发 running 任务上限（洪泛/内存保护：2 × 64MB 缓冲上限）。 */
    private static final int MAX_RUNNING = 2;
    private static final int MAX_TRACKED = 32;
    /** running 任务无新分片的空闲超时（发送端刷新/关页）。 */
    private static final long IDLE_TIMEOUT_MS = 10 * 60_000L;
    /** 终态保留时长（响应丢失后可查询；缓冲已释放）。 */
    private static final long DONE_RETAIN_MS = 5 * 60_000L;

    /** 解析器端口：commit 时对组装完成的 zip 字节执行识别（生产=ModpackService::inspect）。 */
    public interface Inspector {
        ModpackService.ImportResult inspect(byte[] zipBytes);
    }

    private final Inspector inspector;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    /** 任务条目（读写经 synchronized(task) 串行化；分片顺序由 offset 校验兜底）。 */
    static final class Task {
        final String id;
        final String name;
        final long size;
        final String sha256;
        final byte[] buffer;
        volatile long received;
        volatile long lastProgressAt = System.currentTimeMillis();
        volatile long finishedAt;
        volatile String state = "running";
        volatile String error = "";

        Task(String id, String name, long size, String sha256) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.sha256 = sha256.toLowerCase();
            this.buffer = new byte[(int) size];
        }
    }

    public ModpackUploadService(Inspector inspector) {
        this.inspector = inspector;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcpanel-modpack-uploads");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::sweep, 30_000L, 30_000L, TimeUnit.MILLISECONDS);
    }

    /** 开始上传：建任务并预分配缓冲（size/sha256 由浏览器预先确定）。 */
    public Map<String, Object> begin(String name, long size, String sha256) {
        if (size <= 0 || size > MAX_BYTES) {
            throw McpanelBusinessException.invalid("整合包文件大小需在 0 与 64MB 之间");
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw McpanelBusinessException.invalid("缺少有效的整文件 sha256（64 位十六进制）");
        }
        long running = tasks.values().stream().filter(task -> "running".equals(task.state)).count();
        if (running >= MAX_RUNNING) {
            throw new McpanelBusinessException("upload.busy", 429, "已有整合包上传在进行，请稍后再试");
        }
        if (tasks.size() >= MAX_TRACKED) {
            sweep();
        }
        String taskId = UUID.randomUUID().toString().replace("-", "");
        tasks.put(taskId, new Task(taskId,
                name == null || name.isBlank() ? "整合包.zip" : name, size, sha256));
        return view(requireTask(taskId));
    }

    /** 接收浏览器分片（顺序到达，offset 必须等于已收字节）。 */
    public Map<String, Object> chunk(String taskId, long offset, byte[] data) {
        Task task = requireTask(taskId);
        if (data == null || data.length == 0) {
            throw McpanelBusinessException.invalid("分片内容为空");
        }
        if (data.length > MAX_CHUNK_BYTES) {
            throw McpanelBusinessException.invalid("单分片不能超过 8MB");
        }
        synchronized (task) {
            requireRunning(task, "分片被拒绝");
            if (offset != task.received) {
                throw new McpanelBusinessException("upload.offset-mismatch", 409,
                        "分片顺序错误：期望偏移 " + task.received + "，收到 " + offset);
            }
            if (task.received + data.length > task.size) {
                throw McpanelBusinessException.invalid("分片超出声明的文件大小");
            }
            System.arraycopy(data, 0, task.buffer, (int) task.received, data.length);
            task.received += data.length;
            task.lastProgressAt = System.currentTimeMillis();
            return view(task);
        }
    }

    /**
     * 完成上传：校验完整性后同步解析，识别结果落 {@link ModpackInspectStore}（token 供
     * modpack-apply 使用），返回与 /admin/modpacks/inspect 相同的摘要。解析（CF 外呼）
     * 在锁外执行，失败任务置 failed。
     */
    public Map<String, Object> commit(String taskId) {
        Task task = requireTask(taskId);
        byte[] bytes;
        synchronized (task) {
            requireRunning(task, "提交被拒绝");
            if (task.received != task.size) {
                fail(task, "分片不完整：已收 " + task.received + "/" + task.size + " 字节");
                throw new McpanelBusinessException("upload.incomplete", 409,
                        "上传不完整（已收 " + task.received + "/" + task.size + " 字节），请重新选择文件");
            }
            String actual = sha256Hex(task.buffer);
            if (!actual.equals(task.sha256)) {
                fail(task, "sha256 校验失败");
                throw new McpanelBusinessException("upload.sha256-mismatch", 409,
                        "文件校验失败（sha256 不匹配，传输可能损坏），请重新选择文件");
            }
            task.state = "done";
            task.finishedAt = System.currentTimeMillis();
            bytes = task.buffer.clone();
        }
        // 解析在锁外：CF manifest 外呼可能数十秒，不阻塞 cancel/清扫。
        try {
            ModpackService.ImportResult result = inspector.inspect(bytes);
            String token = ModpackInspectStore.newToken();
            ModpackInspectStore.put(token, task.name, result);
            return ModpackService.summarize(token, task.name, result);
        }
        catch (RuntimeException error) {
            synchronized (task) {
                if ("done".equals(task.state)) {
                    task.state = "failed";
                    task.error = "解析失败：" + (error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
                    task.finishedAt = System.currentTimeMillis();
                }
            }
            throw error;
        }
        finally {
            synchronized (task) {
                java.util.Arrays.fill(task.buffer, (byte) 0);
            }
        }
    }

    /** 取消上传：释放缓冲并置 canceled。 */
    public Map<String, Object> cancel(String taskId) {
        Task task = requireTask(taskId);
        synchronized (task) {
            if ("running".equals(task.state)) {
                task.state = "canceled";
                task.error = "已取消";
                task.finishedAt = System.currentTimeMillis();
                java.util.Arrays.fill(task.buffer, (byte) 0);
            }
            return view(task);
        }
    }

    // ---------- 内部 ----------

    private Task requireTask(String taskId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            throw McpanelBusinessException.notFound("上传任务不存在或已过期，请重新选择文件");
        }
        return task;
    }

    private void requireRunning(Task task, String suffix) {
        if (!"running".equals(task.state)) {
            throw new McpanelBusinessException("upload.not-running", 409,
                    "上传任务已结束（" + task.state + (task.error.isBlank() ? "" : "：" + task.error) + "），" + suffix);
        }
    }

    private void fail(Task task, String message) {
        task.state = "failed";
        task.error = message;
        task.finishedAt = System.currentTimeMillis();
        java.util.Arrays.fill(task.buffer, (byte) 0);
    }

    private Map<String, Object> view(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.id);
        item.put("name", task.name);
        item.put("size", task.size);
        item.put("uploaded", task.received);
        item.put("chunkSize", SUGGESTED_CHUNK_BYTES);
        item.put("state", task.state);
        item.put("error", task.error);
        return item;
    }

    /** 清扫：running 空闲超时置失败（释放缓冲），终态过保留期移除。 */
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

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        }
        catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        tasks.clear();
    }
}
