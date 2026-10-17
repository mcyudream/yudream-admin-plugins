package online.yudream.plugin.voxelithmap.application.dto;

/**
 * 分布式分片 worker 状态。
 *
 * <p><b>能力边界（务必让管理员看到）</b>：插件只托管 worker <b>进程</b>，不生产队列。
 * 分片队列的写入方位于 Voxelith 自己的管线侧（{@code RunPipelineUseCase} 的队列模式），
 * 没有命令行入口。因此插件提供共享队列目录，由外部工具/其他 worker 入队；
 * 队列为空时 worker 会在 {@code idleMinutes} 后自行退出。</p>
 *
 * @param mapId           地图 id
 * @param state           {@code RUNNING} / {@code STOPPED} / {@code EXITED}
 * @param pid             子进程 pid；未运行时为 {@code -1}
 * @param workerId        worker 标识
 * @param threads         本地并发线程数
 * @param alive           进程当前是否存活
 * @param queueDir        共享队列目录（多机指向同一目录即可协作）
 * @param queueHint       队列相关的能力说明（前端直接展示）
 * @param artifactDir     worker 直接写入的磁盘产物目录（{@code <publishRoot>/<mapId>/<generationId>}）
 * @param publishedTo     这套磁盘目录对应的发布代 id
 * @param syncedTiles     本次停止时同步回对象存储的产物个数
 * @param startedAt       启动时间（ISO-8601）
 * @param exitNote        退出说明（正常结束/被停止/退出码）
 * @param error           启动或运行错误
 * @param logTail         子进程输出尾巴
 */
public record ShardWorkerStatusDTO(
        String mapId,
        String state,
        long pid,
        String workerId,
        int threads,
        boolean alive,
        String queueDir,
        String queueHint,
        String artifactDir,
        String publishedTo,
        int syncedTiles,
        String startedAt,
        String exitNote,
        String error,
        String logTail) {

    /** 未运行时的空闲状态。 */
    public static ShardWorkerStatusDTO idle(String mapId, String queueDir, String queueHint,
                                            String artifactDir, int threads) {
        return new ShardWorkerStatusDTO(mapId, "STOPPED", -1, null, threads, false,
                queueDir, queueHint, artifactDir, null, 0, null, null, null, "");
    }
}
