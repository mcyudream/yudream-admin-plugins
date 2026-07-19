package online.yudream.plugin.worldmap.application.dto;

/**
 * 地图查看器设置（公开，契约 §3）。
 */
public record MapSettingsDTO(
        String id,
        String name,
        String dimension,
        Spawn spawn,
        int minY,
        int maxY,
        int hiresTileSize,
        int lowresTileSize,
        int lowresMaxLod,
        String generationId,
        String atlasUrl,
        String renderer,
        String blueMapTexturesUrl,
        String blueMapSettingsUrl,
        long renderedAt
) {
    public record Spawn(int x, int y, int z) {
    }
}
