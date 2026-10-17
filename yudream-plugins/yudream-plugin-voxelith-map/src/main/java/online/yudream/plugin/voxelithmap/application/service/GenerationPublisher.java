package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;

import java.util.List;
import java.util.UUID;

/**
 * 发布边界：一代产物要么整体可用，要么完全不存在。
 *
 * <p>渲染是长流程、失败率高（超时、OOM、参数错误），因此产物必须写进**隔离的代目录**，
 * 等全部导入并校验通过后才切换指针。这样「渲染失败把线上地图搞坏了」在机制上不可能发生。</p>
 *
 * <p>与 world-map 的实现差异：那边在内存里记 stagedKeys，进程重启就丢了；这里每代都写一份
 * {@code generation-manifest.txt} 落到对象存储，于是**重启后仍能精确回收失败代的垃圾**
 * （没有这份清单，失败代留下的瓦片将永远无法定位和删除）。</p>
 */
public final class GenerationPublisher {

    private final MapArtifactStorage storage;

    public GenerationPublisher(MapArtifactStorage storage) {
        this.storage = storage;
    }

    /** 开一代新的隔离目录（不改变可见代）。 */
    public MapGeneration stage(String mapId) {
        return new MapGeneration(mapId, newGenerationId());
    }

    /**
     * 发布前校验：代目录必须已经有清单，否则拒绝发布。
     *
     * <p>只校验「有清单且清单登记了产物」——更细的完整性校验由
     * {@code VoxelithArtifactImporter.ImportSummary.complete()} 在导入阶段完成。</p>
     *
     * @throws IllegalStateException 产物缺失
     */
    public void requirePublishable(MapGeneration generation) {
        if (storage.manifest(generation.mapId(), generation.id()).isEmpty()) {
            throw new IllegalStateException("代 " + generation.id() + " 缺少清单，拒绝发布");
        }
        if (storage.readGenerationManifest(generation.mapId(), generation.id()).isEmpty()) {
            throw new IllegalStateException("代 " + generation.id() + " 没有登记任何产物，拒绝发布");
        }
    }

    /** 丢弃一代（失败/取消后的清理）：按该代登记的 key 逐个删。 */
    public void discard(MapGeneration generation) {
        storage.deleteGeneration(generation.mapId(), generation.id());
    }

    /** 删除地图的全部产物（含所有已发布代）。 */
    public void deleteAll(MapProject project) {
        for (String generationId : project.publishedGenerationIds()) {
            storage.deleteGeneration(project.id(), generationId);
        }
        storage.deleteMapInputs(project.id());
    }

    /**
     * 回收超额旧代。
     *
     * @return 实际回收掉的代 id
     */
    public List<String> cleanupOldGenerations(MapProject project) {
        List<String> eligible = project.generationsEligibleForCleanup();
        for (String generationId : eligible) {
            storage.deleteGeneration(project.id(), generationId);
        }
        return eligible;
    }

    /** 代 id：短 UUID，足够唯一又不会把对象 key 撑长。 */
    static String newGenerationId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
