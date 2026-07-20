package online.yudream.plugin.worldmap.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.worldmap.application.assembler.WorldMapAppAssembler;
import online.yudream.plugin.worldmap.application.cmd.CreateMapCmd;
import online.yudream.plugin.worldmap.application.dto.MapAdminDTO;
import online.yudream.plugin.worldmap.application.dto.MapSettingsDTO;
import online.yudream.plugin.worldmap.application.dto.MapSummaryDTO;
import online.yudream.plugin.worldmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;
import online.yudream.plugin.worldmap.domain.enumerate.TaskState;
import online.yudream.plugin.worldmap.domain.repo.MapInstanceRepo;
import online.yudream.plugin.worldmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 地图应用服务：地图生命周期与渲染触发。
 */
public class MapAppService {

    private final MapInstanceRepo mapRepo;
    private final RenderTaskRepo taskRepo;
    private final TileStorage tileStorage;
    private final FrameworkServices framework;
    private final RenderOrchestrator orchestrator;
    private final GenerationPublisher generationPublisher;
    private final WorldMapAppAssembler assembler = new WorldMapAppAssembler();

    public MapAppService(MapInstanceRepo mapRepo,
                         RenderTaskRepo taskRepo,
                         TileStorage tileStorage,
                         FrameworkServices framework,
                         RenderOrchestrator orchestrator) {
        this.mapRepo = mapRepo;
        this.taskRepo = taskRepo;
        this.tileStorage = tileStorage;
        this.framework = framework;
        this.orchestrator = orchestrator;
        this.generationPublisher = new GenerationPublisher(tileStorage);
    }

    public List<MapSummaryDTO> listPublic() {
        return mapRepo.findAll().stream()
                .filter(map -> !isBlank(map.getActiveGenerationId()))
                .map(assembler::toSummary)
                .toList();
    }

    public MapSettingsDTO settings(String mapId) {
        MapInstance map = requireReady(mapId);
        boolean hasBlueMapLowresIndex = "BLUEMAP".equals(map.getActiveRenderer())
                && tileStorage.blueMapLowresIndex(map.getId(), map.getActiveGenerationId()).isPresent();
        return assembler.toSettings(map, hasBlueMapLowresIndex);
    }

    public List<MapAdminDTO> listAdmin() {
        return mapRepo.findAll().stream().map(assembler::toAdmin).toList();
    }

    public MapAdminDTO create(CreateMapCmd cmd) {
        if (cmd == null || isBlank(cmd.name()) || isBlank(cmd.worldFileId())) {
            throw new IllegalArgumentException("地图名称与存档文件不能为空");
        }
        String dimension = isBlank(cmd.dimension()) ? "overworld" : cmd.dimension().trim();
        if (!List.of("overworld", "nether", "the_end").contains(dimension)) {
            throw new IllegalArgumentException("不支持的维度：" + dimension);
        }
        MapInstance map = new MapInstance(UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                cmd.name().trim(), dimension);
        map.setStripNetherCeiling(!Boolean.FALSE.equals(cmd.stripNetherCeiling()));
        map.setWorldZipKey(storePlatformFile(cmd.worldFileId(), map.getId(), "world.zip"));
        if (!isBlank(cmd.clientJarFileId())) {
            map.setClientJarKey(storePlatformFile(cmd.clientJarFileId(), map.getId(), "client.jar"));
        }
        mapRepo.save(map);
        return assembler.toAdmin(map);
    }

    public RenderTaskDTO render(String mapId) {
        MapInstance map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("地图不存在：" + mapId));
        if (isBlank(map.getWorldZipKey())) {
            throw new IllegalArgumentException("地图缺少存档数据，无法渲染");
        }
        Optional<RenderTask> running = taskRepo.findLatest(mapId)
                .filter(task -> task.getState() == TaskState.PENDING || task.getState() == TaskState.RUNNING);
        if (running.isPresent()) {
            return assembler.toDTO(running.get());
        }
        RenderTask task = orchestrator.submit(map);
        return assembler.toDTO(task);
    }

    public void delete(String mapId) {
        MapInstance map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("地图不存在：" + mapId));
        generationPublisher.deletePublished(map);
        tileStorage.deleteMap(map.getId());
        deleteObject(map.getWorldZipKey());
        deleteObject(map.getClientJarKey());
        taskRepo.deleteByMapId(mapId);
        mapRepo.delete(mapId);
    }

    public void cancelTask(String taskId) {
        if (!orchestrator.cancel(taskId)) {
            throw new IllegalArgumentException("任务不存在或已结束：" + taskId);
        }
    }

    public List<RenderTaskDTO> tasks() {
        return taskRepo.findAll().stream().map(assembler::toDTO).toList();
    }

    public MapInstance requireReady(String mapId) {
        MapInstance map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("地图不存在：" + mapId));
        if (isBlank(map.getActiveGenerationId())) {
            throw new IllegalArgumentException("地图尚未渲染完成");
        }
        return map;
    }

    private static final String WORLD_ZIP_KEY = "world.zip";
    private static final String CLIENT_JAR_KEY = "client.jar";

    private String storePlatformFile(String fileId, String mapId, String name) {
        PluginStoredFile file = framework.platformFile(fileId)
                .orElseThrow(() -> new IllegalArgumentException("平台文件不存在：" + fileId));
        try (InputStream input = file.inputStream()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0) {
                throw new IllegalArgumentException("平台文件内容为空：" + fileId);
            }
            if (WORLD_ZIP_KEY.equals(name)) {
                tileStorage.saveWorldZip(mapId, bytes);
            } else {
                tileStorage.saveClientJar(mapId, bytes);
            }
            return "maps/" + mapId + "/" + name;
        } catch (IOException e) {
            throw new IllegalStateException("读取平台文件失败：" + e.getMessage(), e);
        }
    }

    private void deleteObject(String key) {
        if (!isBlank(key)) {
            tileStorage.delete(key);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
