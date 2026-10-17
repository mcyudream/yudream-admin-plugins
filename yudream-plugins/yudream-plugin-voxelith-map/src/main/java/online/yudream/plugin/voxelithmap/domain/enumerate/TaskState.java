package online.yudream.plugin.voxelithmap.domain.enumerate;

/** 渲染任务状态。 */
public enum TaskState {
    /** 已入队未开始。 */
    PENDING,
    /** 渲染子进程运行中。 */
    RUNNING,
    /** 成功并已发布。 */
    SUCCEEDED,
    /** 失败（含超时、参数错误、内存不足）。 */
    FAILED,
    /** 被取消。 */
    CANCELED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
