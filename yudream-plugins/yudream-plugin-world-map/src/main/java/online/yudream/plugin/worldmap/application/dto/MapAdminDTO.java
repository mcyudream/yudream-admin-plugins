package online.yudream.plugin.worldmap.application.dto;

/**
 * 地图管理视图（含渲染统计与状态）。
 */
public record MapAdminDTO(
        String id,
        String name,
        String dimension,
        String state,
        int hiresTiles,
        int lowresTiles,
        long createdAt,
        long renderedAt,
        String message
) {
}
