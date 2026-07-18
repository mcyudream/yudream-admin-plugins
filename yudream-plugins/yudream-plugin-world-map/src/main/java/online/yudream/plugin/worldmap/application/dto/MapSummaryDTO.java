package online.yudream.plugin.worldmap.application.dto;

/**
 * 地图摘要（公开）。
 */
public record MapSummaryDTO(
        String id,
        String name,
        String dimension,
        String state,
        long renderedAt
) {
}
