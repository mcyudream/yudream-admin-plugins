package online.yudream.plugin.worldmap.application.dto;

/**
 * 渲染任务视图。
 */
public record RenderTaskDTO(
        String id,
        String mapId,
        String state,
        String phase,
        int progressPercent,
        int totalTiles,
        int doneTiles,
        String message,
        long createdAt,
        long startedAt,
        long finishedAt,
        String error
) {
}
