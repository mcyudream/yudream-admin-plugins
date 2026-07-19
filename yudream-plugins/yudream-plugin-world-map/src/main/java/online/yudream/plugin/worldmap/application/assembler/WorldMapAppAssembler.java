package online.yudream.plugin.worldmap.application.assembler;

import online.yudream.plugin.worldmap.application.dto.MapAdminDTO;
import online.yudream.plugin.worldmap.application.dto.MapSettingsDTO;
import online.yudream.plugin.worldmap.application.dto.MapSummaryDTO;
import online.yudream.plugin.worldmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;

/**
 * 地图/任务聚合到 DTO 的装配。
 */
public class WorldMapAppAssembler {

    public MapSummaryDTO toSummary(MapInstance map) {
        return new MapSummaryDTO(
                map.getId(),
                map.getName(),
                map.getDimension(),
                map.getState().name(),
                map.getRenderedAt()
        );
    }

    public MapSettingsDTO toSettings(MapInstance map) {
        return new MapSettingsDTO(
                map.getId(),
                map.getName(),
                map.getDimension(),
                new MapSettingsDTO.Spawn(map.getSpawnX(), map.getSpawnY(), map.getSpawnZ()),
                map.getMinY(),
                map.getMaxY(),
                32,
                512,
                4,
                map.getActiveGenerationId(),
                "generations/" + map.getActiveGenerationId() + "/textures/atlas.png",
                map.getActiveRenderer(),
                "BLUEMAP".equals(map.getActiveRenderer())
                        ? "generations/" + map.getActiveGenerationId() + "/textures.json" : null,
                "BLUEMAP".equals(map.getActiveRenderer())
                        ? "generations/" + map.getActiveGenerationId() + "/settings.json" : null,
                map.getRenderedAt()
        );
    }

    public MapAdminDTO toAdmin(MapInstance map) {
        return new MapAdminDTO(
                map.getId(),
                map.getName(),
                map.getDimension(),
                map.getState().name(),
                map.getHiresTiles(),
                map.getLowresTiles(),
                map.getCreatedAt(),
                map.getRenderedAt(),
                map.getMessage()
        );
    }

    public RenderTaskDTO toDTO(RenderTask task) {
        return new RenderTaskDTO(
                task.getId(),
                task.getMapId(),
                task.getState().name(),
                task.getPhase().name(),
                task.getProgressPercent(),
                task.getTotalTiles(),
                task.getDoneTiles(),
                task.getMessage(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getError()
        );
    }
}
