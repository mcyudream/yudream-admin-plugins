package online.yudream.plugin.voxelithmap.application.assembler;

import online.yudream.plugin.voxelithmap.application.dto.MapAdminDTO;
import online.yudream.plugin.voxelithmap.application.dto.MapSummaryDTO;
import online.yudream.plugin.voxelithmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.util.ArrayList;
import java.util.List;

/** 领域对象 → DTO。 */
public final class VoxelithAppAssembler {

    private VoxelithAppAssembler() {
    }

    public static MapSummaryDTO toSummary(MapProject project) {
        MapManifestSummary summary = project.summary();
        List<Double> boundsMin = summary.boundsMin();
        List<Double> boundsMax = summary.boundsMax();
        return new MapSummaryDTO(
                project.id(),
                project.name(),
                project.dimension(),
                project.hasPublishedArtifacts(),
                summary.version(),
                summary.tileCount(),
                boundsMin,
                boundsMax,
                center(boundsMin, boundsMax),
                List.of(project.spawnX(), project.spawnY(), project.spawnZ()),
                summary.generatedAt());
    }

    public static MapAdminDTO toAdmin(MapProject project, long coverRevision) {
        MapManifestSummary summary = project.summary();
        return new MapAdminDTO(
                project.id(),
                project.name(),
                project.worldName(),
                project.worldFileId(),
                project.clientJarFileId(),
                project.clientJarName(),
                project.clientJarFileId() != null && !project.clientJarFileId().isBlank(),
                project.modsPackFileId().orElse(null),
                project.modsPackName(),
                project.modJars().stream()
                        .map(modJar -> new MapAdminDTO.ModJarDTO(modJar.fileId(), modJar.name()))
                        .toList(),
                project.dimension(),
                List.of(project.spawnX(), project.spawnY(), project.spawnZ()),
                project.state().name(),
                project.activeGenerationId(),
                project.publishedGenerationIds(),
                project.region().map(VoxelithAppAssembler::toRegion).orElse(null),
                toSettings(project.settings()),
                summary.describeScale(),
                summary.boundsMin(),
                summary.boundsMax(),
                project.lastError(),
                project.createdAt().toString(),
                project.updatedAt().toString(),
                coverRevision,
                project.archived(),
                project.sourceInstanceId().orElse(null),
                project.worldGroup());
    }

    public static RenderTaskDTO toTask(RenderTask task, String mapName) {
        // 作业类任务（导入/同步/删除）没有地图：主体用任务自带的 subject（来源实例名等），
        // 不再回查地图文档——列表接口为每个任务多读一次存储，正是要避免的读放大
        String subject = task.subject().isBlank()
                ? (mapName == null ? task.mapId() : mapName)
                : task.subject();
        return new RenderTaskDTO(
                task.id(),
                task.kind().name(),
                task.kind().label(),
                subject,
                task.mapId(),
                mapName == null ? task.mapId() : mapName,
                task.generationId(),
                task.state().name(),
                task.phase().name(),
                task.phase().label(),
                task.percent(),
                task.completedTiles(),
                task.totalTiles(),
                task.message(),
                task.error(),
                task.logTail(),
                task.diagnostics(),
                task.canCancel(),
                task.createdAt().toString(),
                task.startedAt() == null ? null : task.startedAt().toString(),
                task.finishedAt() == null ? null : task.finishedAt().toString());
    }

    private static MapAdminDTO.RegionDTO toRegion(RegionWindow region) {
        return new MapAdminDTO.RegionDTO(region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                region.widthBlocks(), region.depthBlocks(), region.chunkCount());
    }

    private static MapAdminDTO.RenderSettingsDTO toSettings(RenderSettings settings) {
        return new MapAdminDTO.RenderSettingsDTO(settings.minY(), settings.maxLevel(), settings.lodAtlas(),
                settings.meshopt(), settings.maxChunks(), settings.batchChunks(), settings.skipHarvest(),
                settings.mcVersion(), settings.loaderVersion());
    }

    /** 包围盒中心；缺数据时退化为出生点方向上的一点，避免前端拿到 NaN 机位。 */
    static List<Double> center(List<Double> boundsMin, List<Double> boundsMax) {
        List<Double> result = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            double min = index < boundsMin.size() ? boundsMin.get(index) : 0d;
            double max = index < boundsMax.size() ? boundsMax.get(index) : 0d;
            result.add((min + max) / 2);
        }
        return List.copyOf(result);
    }

    /** 阶段枚举 → 中文名（SSE 里给前端直接用，省得前端维护一份映射）。 */
    public static String labelOf(RenderPhase phase) {
        return phase.label();
    }
}
