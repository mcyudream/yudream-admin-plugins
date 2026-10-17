package online.yudream.plugin.voxelithmap.application.dto;

/**
 * 渲染任务视图（也用于 SSE 推送）。
 *
 * @param id             任务 id
 * @param mapId          地图 id
 * @param mapName        地图名（列表展示省一次联查）
 * @param generationId   目标发布代
 * @param state          任务状态
 * @param phase          阶段枚举名
 * @param phaseLabel     阶段中文名
 * @param percent        整体百分比
 * @param completedTiles 已完成瓦片
 * @param totalTiles     瓦片总数
 * @param message        当前动作描述
 * @param error          失败原因
 * @param logTail        日志尾部
 * @param diagnostics    引擎关键诊断（版本体检警告、模型来源、实体与地图画统计…）
 * @param canCancel      是否可取消
 * @param createdAt      入队时间
 * @param startedAt      开始时间
 * @param finishedAt     结束时间
 */
public record RenderTaskDTO(
        String id,
        /** 任务类型：RENDER / IMPORT / SYNC / DELETE（任务详情页按它分组与展示）。 */
        String kind,
        /** 类型展示名（渲染 / 实例导入 / 存档同步 / 删除地图）。 */
        String kindLabel,
        /** 展示主体：渲染任务＝地图名，导入任务＝来源实例名。 */
        String subject,
        String mapId,
        String mapName,
        String generationId,
        String state,
        String phase,
        String phaseLabel,
        int percent,
        int completedTiles,
        int totalTiles,
        String message,
        String error,
        String logTail,
        java.util.List<String> diagnostics,
        boolean canCancel,
        String createdAt,
        String startedAt,
        String finishedAt) {
}
