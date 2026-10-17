package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.support.InMemoryFileStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布边界测试。
 *
 * <p>这套机制的唯一职责是「半成品永远不能变成线上内容」：产物先写在隔离代目录里，
 * 只有清单完整才允许发布；失败/取消走 {@code discard}，线上指针一动不动。</p>
 */
class GenerationPublisherTest {

    private final InMemoryFileStore store = new InMemoryFileStore();
    private final MapArtifactStorage storage = new MapArtifactStorage(store);
    private final GenerationPublisher publisher = new GenerationPublisher(storage);

    private static MapProject project(String id) {
        return MapProject.create(id, "测试地图", "world", "file-world", "file-jar", "client-1.21.1.jar",
                java.util.List.of(), 0, 64, 0, RenderSettings.defaults());
    }

    /** 模拟一次成功的渲染导入：写清单 + 登记产物。 */
    private void importGeneration(String mapId, String generationId, String... relativEs) {
        storage.saveArtifact(mapId, generationId, MapArtifactStorage.MANIFEST_FILE,
                "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8));
        List<String> keys = new java.util.ArrayList<>();
        keys.add(MapArtifactStorage.artifactKey(mapId, generationId,
                MapArtifactStorage.MANIFEST_FILE));
        for (String relative : relativEs) {
            storage.saveArtifact(mapId, generationId, relative, "X".getBytes(StandardCharsets.UTF_8));
            keys.add(MapArtifactStorage.artifactKey(mapId, generationId, relative));
        }
        storage.writeGenerationManifest(mapId, generationId, keys);
    }

    @Test
    void 缺清单的代拒绝发布() {
        MapGeneration generation = publisher.stage("m1");
        assertThrows(IllegalStateException.class, () -> publisher.requirePublishable(generation));
    }

    @Test
    void 有清单但没登记产物的代也拒绝发布() {
        MapGeneration generation = publisher.stage("m1");
        storage.saveArtifact("m1", generation.id(), MapArtifactStorage.MANIFEST_FILE,
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> publisher.requirePublishable(generation));
    }

    @Test
    void 清单完整的代可以发布() {
        MapGeneration generation = publisher.stage("m1");
        importGeneration("m1", generation.id(), "tiles/hires/0/0.glb");
        publisher.requirePublishable(generation);
        assertEquals("m1", generation.mapId());
    }

    @Test
    void 新代id不会互相重复() {
        assertFalse(publisher.stage("m1").id().equals(publisher.stage("m1").id()));
    }

    @Test
    void 丢弃失败代只影响它自己() {
        MapGeneration good = publisher.stage("m1");
        importGeneration("m1", good.id(), "tiles/hires/0/0.glb");

        MapGeneration broken = publisher.stage("m1");
        importGeneration("m1", broken.id(), "tiles/hires/9/9.glb");

        publisher.discard(broken);

        assertFalse(storage.artifact("m1", broken.id(), "tiles/hires/9/9.glb").isPresent(),
                "失败代产物应被清掉");
        assertTrue(storage.artifact("m1", good.id(), "tiles/hires/0/0.glb").isPresent(),
                "已完成代不受影响");
    }

    @Test
    void 超过上限的旧代被回收() {
        MapProject project = project("m1");
        // 发布 4 代（上限 3），最老的一代应进入可回收名单
        for (int i = 0; i < MapGeneration.MAX_PUBLISHED_GENERATIONS + 1; i++) {
            MapGeneration generation = publisher.stage("m1");
            importGeneration("m1", generation.id(), "tiles/hires/0/" + i + ".glb");
            project.publish(generation, MapManifestSummary.empty());
        }
        List<String> recycled = publisher.cleanupOldGenerations(project);
        assertEquals(1, recycled.size(), "应恰好回收一代");
        assertFalse(storage.artifact("m1", recycled.get(0), "tiles/hires/0/0.glb").isPresent());
    }

    @Test
    void 删除地图会清掉全部已发布代与输入() {
        MapProject project = project("m1");
        MapGeneration generation = publisher.stage("m1");
        importGeneration("m1", generation.id(), "tiles/hires/0/0.glb");
        project.publish(generation, MapManifestSummary.empty());
        storage.saveWorldArchive("m1", "zip".getBytes(StandardCharsets.UTF_8));
        storage.saveMarkers("m1", "{}".getBytes(StandardCharsets.UTF_8));

        publisher.deleteAll(project);

        assertFalse(storage.artifact("m1", generation.id(), "tiles/hires/0/0.glb").isPresent());
        assertFalse(storage.worldArchive("m1").isPresent());
        assertFalse(storage.markers("m1").isPresent());
    }
}
