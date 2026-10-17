package online.yudream.plugin.voxelithmap.infrastructure.storage;

import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.voxelithmap.support.InMemoryFileStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 产物存储的 key 布局、相对路径校验与按代回收测试（内存 PluginFileStore）。 */
class MapArtifactStorageTest {

    private final InMemoryFileStore store = new InMemoryFileStore();
    private final MapArtifactStorage storage = new MapArtifactStorage(store);

    private static List<String> keysOf(String mapId, String generationId, String... relativEs) {
        return java.util.Arrays.stream(relativEs)
                .map(relative -> MapArtifactStorage.artifactKey(mapId, generationId, relative))
                .toList();
    }

    @Test
    void key布局符合契约() {
        assertEquals("maps/m1/generations/gen01/manifest.json",
                MapArtifactStorage.artifactKey("m1", "gen01", "manifest.json"));
        assertEquals("maps/m1/generations/gen01",
                MapArtifactStorage.generationPrefix("m1", "gen01"));
        assertEquals("maps/m1/markers.json", MapArtifactStorage.markersKey("m1"));
        // 代清单也落在代前缀内：一代的全部对象都归一个前缀管，回收不必另开分支
        assertEquals("maps/m1/generations/gen01/generation-manifest.txt",
                MapArtifactStorage.generationManifestKey("m1", "gen01"));
        assertEquals("maps/m1/world.zip", MapArtifactStorage.worldArchiveKey("m1"));
    }

    @Test
    void 产物读写往返() throws Exception {
        storage.saveArtifact("m1", "gen01", "tiles/hires/0/0.glb",
                "TILE".getBytes(StandardCharsets.UTF_8));
        PluginStoredFile file = storage.artifact("m1", "gen01", "tiles/hires/0/0.glb").orElseThrow();
        try (InputStream input = file.inputStream()) {
            assertEquals("TILE", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("model/gltf-binary", file.contentType(), "contentType 应按后缀推导");
    }

    @Test
    void 标注是地图级而不是代级() {
        storage.saveMarkers("m1", "{\"sets\":[]}".getBytes(StandardCharsets.UTF_8));
        assertTrue(storage.markers("m1").isPresent());
        assertFalse(storage.artifact("m1", "gen01", "markers.json").isPresent(),
                "标注不该出现在代目录里");
    }

    @Test
    void 拒绝越界相对路径() {
        for (String bad : List.of("../escape.json", "tiles/../../escape.json", "/abs.json",
                "C:/abs.json", "tiles//double.glb", "", "  ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MapArtifactStorage.requireRelative(bad), "应拒绝：" + bad);
        }
    }

    @Test
    void 反斜杠被规范化而不是拒绝() {
        // Windows 侧清单里出现过反斜杠：规范化成 / 才能与 key 前缀对齐
        assertEquals("tiles/hires/0.glb", MapArtifactStorage.requireRelative("tiles\\hires\\0.glb"));
    }

    @Test
    void 内容类型按后缀推导() {
        assertEquals("model/gltf-binary", MapArtifactStorage.contentTypeOf("tiles/hires/0/0.glb"));
        assertEquals("image/png", MapArtifactStorage.contentTypeOf("atlas.png"));
        assertEquals("application/json", MapArtifactStorage.contentTypeOf("manifest.json"));
        assertEquals("image/png", MapArtifactStorage.contentTypeOf("panorama.png"));
    }

    @Test
    void 按代回收只删该代登记的key() {
        storage.saveArtifact("m1", "gen01", "manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.saveArtifact("m1", "gen01", "tiles/hires/0/0.glb", "A".getBytes(StandardCharsets.UTF_8));
        // 代清单登记的是「完整 key」（导入器写入的就是这个形态，回收时按前缀过滤）
        storage.writeGenerationManifest("m1", "gen01",
                keysOf("m1", "gen01", "manifest.json", "tiles/hires/0/0.glb"));

        storage.saveArtifact("m1", "gen02", "manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.writeGenerationManifest("m1", "gen02", keysOf("m1", "gen02", "manifest.json"));
        storage.saveMarkers("m1", "{\"sets\":[]}".getBytes(StandardCharsets.UTF_8));

        storage.deleteGeneration("m1", "gen01");

        assertFalse(storage.artifact("m1", "gen01", "tiles/hires/0/0.glb").isPresent(),
                "该代瓦片应被回收");
        assertTrue(storage.artifact("m1", "gen02", "manifest.json").isPresent(),
                "其他代不受影响");
        assertTrue(storage.markers("m1").isPresent(), "标注是地图级，不随代回收");
    }

    @Test
    void 代清单登记非本代key时不会被误删() {
        // 防御：清单被写坏（混入别的代的 key）时，回收只能删本代前缀下的东西
        storage.saveArtifact("m1", "gen02", "manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.writeGenerationManifest("m1", "gen01",
                List.of(MapArtifactStorage.artifactKey("m1", "gen02", "manifest.json")));

        storage.deleteGeneration("m1", "gen01");

        assertTrue(storage.artifact("m1", "gen02", "manifest.json").isPresent(),
                "别的代必须毫发无损");
    }

    @Test
    void 代清单可读写() {
        storage.writeGenerationManifest("m1", "gen01", List.of("a.json", "b.png"));
        assertEquals(List.of("a.json", "b.png"), storage.readGenerationManifest("m1", "gen01"));
        assertTrue(storage.readGenerationManifest("m1", "nope").isEmpty());
    }

    @Test
    void 删除地图输入不影响其他地图() {
        storage.saveWorldArchive("m2", "zip-other".getBytes(StandardCharsets.UTF_8));
        storage.saveMarkers("m2", "{}".getBytes(StandardCharsets.UTF_8));

        storage.saveWorldArchive("m1", "zip-mine".getBytes(StandardCharsets.UTF_8));
        storage.saveClientJar("m1", "jar".getBytes(StandardCharsets.UTF_8));
        storage.saveMarkers("m1", "{\"sets\":[]}".getBytes(StandardCharsets.UTF_8));

        storage.deleteMapInputs("m1");

        assertFalse(storage.worldArchive("m1").isPresent(), "存档应被删除");
        assertFalse(storage.clientJar("m1").isPresent(), "资源包应被删除");
        assertFalse(storage.markers("m1").isPresent(), "标注应被删除");
        assertTrue(storage.worldArchive("m2").isPresent(), "其他地图不受影响");
        assertTrue(storage.markers("m2").isPresent());
    }

    @Test
    void 读不到的对象按不存在处理而不是抛异常() {
        // 宿主 PluginFileStore.get 在对象缺失时抛异常（S3 NoSuchKey → BizException），
        // 存储层若不收敛，一张刚建好还没渲染的地图就会让管理列表整个 400
        assertTrue(storage.worldArchive("nope").isEmpty());
        assertTrue(storage.clientJar("nope").isEmpty());
        assertTrue(storage.markers("nope").isEmpty());
        assertTrue(storage.artifact("nope", "gen01", "manifest.json").isEmpty());
        assertTrue(storage.manifest("nope", "gen01").isEmpty());
        assertTrue(storage.sidecarLink("nope").isEmpty());
        assertTrue(storage.readGenerationManifest("nope", "gen01").isEmpty());
    }

    @Test
    void 测试替身复刻宿主未命中即抛异常的语义() {
        // 替身若宽容返回 null，上面那条 bug 类在单测里永远发现不了
        assertThrows(InMemoryFileStore.MissingObjectException.class,
                () -> store.get(MapArtifactStorage.markersKey("nope")));
    }

    @Test
    void 边车映射按地图存小文件() {
        storage.saveSidecarLink("m1", "file-1", "upload-abc");
        String[] link = storage.sidecarLink("m1").orElseThrow();
        assertEquals("file-1", link[0]);
        assertEquals("upload-abc", link[1]);
        assertTrue(storage.sidecarLink("m2").isEmpty(), "其他地图没有映射");
    }

    /**
     * LOD 图集页的对象键布局回归（2D 地图取图通道依赖它）。
     *
     * <p>清单里的页地址 {@code tiles/lod/{lvl}/lod-atlas.png} 必须原样落在本代前缀之下；
     * 宿主文件存储再把对象键映射到磁盘（如 {@code data/voxelith-map/work/{代id}/…}）——
     * 线上排障时就是按磁盘路径反查的对象键，这里钉住「清单地址 → 对象键」这一段不会变。</p>
     */
    @Test
    void LOD图集页对象键按清单地址落在本代前缀下() {
        assertEquals("maps/m1/generations/g1/tiles/lod/1/lod-atlas.png",
                MapArtifactStorage.artifactKey("m1", "g1", "tiles/lod/1/lod-atlas.png"));
        // 图集页与 LOD 瓦片同树：瓦片能读到，图集页也能（同一 find() 路径）
        assertEquals("maps/m1/generations/g1/tiles/lod/2/22/14.glb",
                MapArtifactStorage.artifactKey("m1", "g1", "tiles/lod/2/22/14.glb"));
    }
}
