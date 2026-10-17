package online.yudream.plugin.voxelithmap.domain.aggregate;

import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskState;

import java.time.Instant;
import java.util.List;

/** 任务日志尾部保留的字符数（失败时随错误一起回显，便于在管理页定位问题）。 */
final class RenderTaskLimits {
    static final int MAX_LOG_TAIL_CHARS = 4_000;
    static final int MAX_MESSAGE_CHARS = 400;
    /** 诊断条目上限（与 {@code VoxelithDiagnostics.MAX_LINES} 对齐，多出来的丢弃）。 */
    static final int MAX_DIAGNOSTICS = 24;

    private RenderTaskLimits() {
    }
}

/**
 * 渲染任务聚合。
 *
 * <p>进度是「阶段 + 阶段内百分比」的组合：Voxelith 的 stdout 只在少数关键行给出确定进度，
 * 因此整体百分比 = 阶段权重 + 阶段内推进量，且**只允许前进**（回退会让人以为卡住了）。</p>
 *
 * <p>{@code diagnostics} 与 {@code logTail} 分工明确：日志尾巴只有最后 4000 字，而版本体检警告、
 * 「模型来源」这类关键行出现在<b>开头</b>——跑完必然被瓦片日志挤掉，因此单独结构化保留。</p>
 */
public final class RenderTask {

    private final String id;
    private final TaskKind kind;
    /** 展示主体：渲染任务＝地图名，导入任务＝来源实例名（任务详情页直接显示，不再回查存储）。 */
    private final String subject;
    private final String mapId;
    private final String generationId;
    private TaskState state;
    private RenderPhase phase;
    private int percent;
    private int completedTiles;
    private int totalTiles;
    private String message;
    private String logTail;
    private List<String> diagnostics = List.of();
    private String error;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    private RenderTask(String id, TaskKind kind, String subject, String mapId, String generationId,
                       TaskState state, RenderPhase phase,
                       int percent, int completedTiles, int totalTiles, String message, String logTail,
                       List<String> diagnostics, String error, Instant createdAt, Instant startedAt,
                       Instant finishedAt) {
        this.id = requireText(id, "任务 id");
        this.kind = kind == null ? TaskKind.RENDER : kind;
        this.subject = subject == null ? "" : subject;
        // 只有渲染任务必须绑定地图与代；导入/同步/删除在建图之前就开始了，此时还没有 mapId
        this.mapId = this.kind == TaskKind.RENDER ? requireText(mapId, "mapId") : (mapId == null ? "" : mapId);
        this.generationId = this.kind == TaskKind.RENDER
                ? requireText(generationId, "generationId")
                : (generationId == null ? "" : generationId);
        this.state = state;
        this.phase = phase;
        this.percent = percent;
        this.completedTiles = completedTiles;
        this.totalTiles = totalTiles;
        this.message = message;
        this.logTail = logTail;
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.error = error;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** 新建待执行任务。 */
    public static RenderTask create(String id, MapGeneration generation) {
        return new RenderTask(id, TaskKind.RENDER, "", generation.mapId(), generation.id(),
                TaskState.PENDING, RenderPhase.QUEUED,
                0, 0, 0, RenderPhase.QUEUED.label(), null, List.of(), null, Instant.now(), null, null);
    }

    /**
     * 新建一个不绑定渲染代的长任务（导入/同步/删除）。
     *
     * @param subject 展示主体（如来源实例名、地图名），任务列表与详情页直接显示
     */
    public static RenderTask createJob(String id, TaskKind kind, String subject) {
        return createJob(id, kind, subject, "");
    }

    /**
     * 新建一个绑定地图但不绑定渲染代的长任务（如存档同步：地图还在，代由后续渲染产生）。
     */
    public static RenderTask createJob(String id, TaskKind kind, String subject, String mapId) {
        return new RenderTask(id, kind, subject, mapId, "",
                TaskState.PENDING, RenderPhase.QUEUED,
                0, 0, 0, RenderPhase.QUEUED.label(), null, List.of(), null, Instant.now(), null, null);
    }

    /** 仓储重建入口。 */
    public static RenderTask restore(String id, String mapId, String generationId, TaskState state,
                                     RenderPhase phase, int percent, int completedTiles, int totalTiles,
                                     String message, String logTail, List<String> diagnostics, String error,
                                     Instant createdAt, Instant startedAt, Instant finishedAt) {
        return restore(id, TaskKind.RENDER, "", mapId, generationId, state, phase, percent, completedTiles,
                totalTiles, message, logTail, diagnostics, error, createdAt, startedAt, finishedAt);
    }

    /** 仓储重建入口（带任务类型；旧记录缺 kind 时按渲染任务还原）。 */
    public static RenderTask restore(String id, TaskKind kind, String subject, String mapId, String generationId,
                                     TaskState state, RenderPhase phase, int percent, int completedTiles,
                                     int totalTiles, String message, String logTail, List<String> diagnostics,
                                     String error, Instant createdAt, Instant startedAt, Instant finishedAt) {
        return new RenderTask(id, kind, subject, mapId, generationId, state, phase, percent, completedTiles,
                totalTiles, message, logTail, diagnostics, error, createdAt, startedAt, finishedAt);
    }

    // ---------- 查询 ----------

    public String id() {
        return id;
    }

    /** 任务类型（渲染 / 导入 / 同步 / 删除）。 */
    public TaskKind kind() {
        return kind;
    }

    /** 展示主体：渲染任务为空时由地图名兜底，导入任务为来源实例名。 */
    public String subject() {
        return subject;
    }

    public String mapId() {
        return mapId;
    }

    public String generationId() {
        return generationId;
    }

    public MapGeneration generation() {
        if (kind != TaskKind.RENDER) {
            throw new IllegalStateException("任务 " + id + " 是" + kind.label() + "任务，没有渲染代");
        }
        return new MapGeneration(mapId, generationId);
    }

    public TaskState state() {
        return state;
    }

    public RenderPhase phase() {
        return phase;
    }

    public int percent() {
        return percent;
    }

    public int completedTiles() {
        return completedTiles;
    }

    public int totalTiles() {
        return totalTiles;
    }

    public String message() {
        return message;
    }

    public String logTail() {
        return logTail;
    }

    /** 引擎关键诊断（版本体检警告、模型来源、实体与地图画统计…）。 */
    public List<String> diagnostics() {
        return diagnostics;
    }

    public String error() {
        return error;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public boolean isRunning() {
        return state == TaskState.RUNNING;
    }

    /**
     * 是否可取消。
     *
     * <p>删除作业一旦开始执行就是一次不可中断的存储回收：中途标成「已取消」会与实际情况
     * 不符（产物其实已经删掉了）。所以删除任务只在排队阶段可取消。</p>
     */
    public boolean canCancel() {
        if (kind == TaskKind.DELETE) {
            return state == TaskState.PENDING;
        }
        return state == TaskState.PENDING || state == TaskState.RUNNING;
    }

    // ---------- 变更 ----------

    /** 子进程已启动。 */
    public void start() {
        if (state.isFinished()) {
            throw new IllegalStateException("任务已结束，不能重新开始");
        }
        this.state = TaskState.RUNNING;
        this.startedAt = Instant.now();
        this.message = RenderPhase.SCAN.label();
    }

    /**
     * 推进阶段与进度。
     *
     * <p>单调递增：阶段只前进、百分比只上升。子进程重打日志（例如多遍渲染每批都打印
     * 「bake 完成」）不会被误判成回退。</p>
     */
    public void advance(RenderPhase next, int phasePercent) {
        if (state.isFinished()) {
            return;
        }
        if (next != null && next.ordinal() > phase.ordinal()) {
            this.phase = next;
            this.message = next.label();
        }
        int clamped = Math.max(0, Math.min(100, phasePercent));
        int candidate = Math.max(percent, clamped);
        this.percent = candidate;
    }

    /** 已提交的瓦片数推进（TILE/LOD 阶段的细粒度进度）。 */
    public void reportTiles(int completed, int total) {
        this.completedTiles = Math.max(this.completedTiles, Math.max(0, completed));
        this.totalTiles = Math.max(0, total);
    }

    /** 更新展示用消息（识别不出阶段的关键行原文）。 */
    public void note(String value) {
        if (value == null || value.isBlank() || state.isFinished()) {
            return;
        }
        this.message = value.length() > RenderTaskLimits.MAX_MESSAGE_CHARS
                ? value.substring(0, RenderTaskLimits.MAX_MESSAGE_CHARS)
                : value;
    }

    /**
     * 覆盖诊断快照。
     *
     * <p>引擎输出会在运行中反复刷新（同一行可能重打），所以相同内容直接跳过——否则每 750ms
     * 一次的落库都得重写一遍文档。</p>
     */
    public void recordDiagnostics(List<String> value) {
        if (value == null || value.isEmpty() || value.equals(diagnostics)) {
            return;
        }
        this.diagnostics = value.size() > RenderTaskLimits.MAX_DIAGNOSTICS
                ? List.copyOf(value.subList(0, RenderTaskLimits.MAX_DIAGNOSTICS))
                : List.copyOf(value);
    }

    /** 追加子进程输出行，只保留尾部窗口。 */
    public void appendLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String next = logTail == null ? line : logTail + "\n" + line;
        if (next.length() > RenderTaskLimits.MAX_LOG_TAIL_CHARS) {
            next = next.substring(next.length() - RenderTaskLimits.MAX_LOG_TAIL_CHARS);
        }
        this.logTail = next;
    }

    /** 成功（此时产物已导入并发布，由调用方保证顺序）。 */
    public void succeed() {
        this.state = TaskState.SUCCEEDED;
        this.phase = RenderPhase.DONE;
        this.percent = 100;
        this.message = RenderPhase.DONE.label();
        this.error = null;
        this.finishedAt = Instant.now();
    }

    public void fail(String reason) {
        this.state = TaskState.FAILED;
        this.error = reason;
        this.message = kind.label() + "失败";
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        if (state.isFinished()) {
            return;
        }
        this.state = TaskState.CANCELED;
        this.message = "已取消";
        this.finishedAt = Instant.now();
    }

    /** 进程启动前的参数校验失败（未跑就结束）。 */
    public void reject(String reason) {
        this.state = TaskState.FAILED;
        this.error = reason;
        this.message = "任务未执行";
        this.finishedAt = Instant.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }
}
