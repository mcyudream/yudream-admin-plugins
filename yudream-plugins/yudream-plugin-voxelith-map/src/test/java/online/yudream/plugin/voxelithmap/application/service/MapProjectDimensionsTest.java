package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.dto.MapAdminDTO;
import online.yudream.plugin.voxelithmap.application.dto.MapSummaryDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度分组查询（渲染页/展示页的切换维度按钮的数据来源）。
 *
 * <p>两端的差别是这段逻辑的全部要点：展示页只列**已发布**的维度（没渲染完的切过去只会是空白），
 * 渲染页要列**全部**（管理员正要看哪些还没渲染）。顺序也必须固定，否则按钮位置每次都在跳。</p>
 *
 * <p>只用到仓储与装配器，所以其它依赖传 null：一旦实现里误用了编排器/预览/侧车，
 * 这些用例会立刻以 NPE 报出来。</p>
 */
class MapProjectDimensionsTest {

    private final InMemoryProjectRepo repo = new InMemoryProjectRepo();
    private final MapProjectAppService service =
            new MapProjectAppService(repo, null, null, null, null, null, null, null);

    /** 建一张图并按需发布/归档。 */
    private MapProject save(String id, String name, String dimension, String worldFileId,
                            boolean published, boolean archived) {
        return save(id, name, dimension, worldFileId, published, archived, null);
    }

    /** 同上，另可指定来源实例（同一实例的各维度即便分几次导入也要归一组）。 */
    private MapProject save(String id, String name, String dimension, String worldFileId,
                            boolean published, boolean archived, String sourceInstanceId) {
        MapProject project = MapProject.create(id, name, "world", worldFileId,
                "", "", List.of(), 0, 64, 0, RenderSettings.defaults());
        // create 的 settings 决定 dimension，这里按需覆盖成目标维度
        project.updateSettings(new RenderSettings(dimension, project.settings().minY(),
                project.settings().maxLevel(), project.settings().sampleChunks(), project.settings().lodAtlas(),
                project.settings().meshopt(), project.settings().maxChunks(), project.settings().batchChunks(),
                project.settings().skipHarvest(), project.settings().mcVersion(),
                project.settings().loaderVersion()));
        if (sourceInstanceId != null) {
            project.setSourceInstanceId(sourceInstanceId);
        }
        if (published) {
            project.publish(new MapGeneration(id, "g-" + id), MapManifestSummary.empty());
        }
        if (archived) {
            project.archive();
        }
        return repo.save(project);
    }

    @Test
    void adminDimensionsListEveryDimensionOfThatWorldInAFixedOrder() {
        // 乱序放入：主世界 / 末地 / 下界，另外混入其它世界的图与无维度关系的图
        save("m-end", "世界 · 末地", "minecraft:the_end", "archive-a", false, false);
        save("m-nether", "世界 · 下界", "minecraft:nether", "archive-a", false, false);
        save("m-over", "世界", "minecraft:overworld", "archive-a", true, false);
        save("other-world", "别的世界", "minecraft:overworld", "archive-b", true, false);

        List<String> ids = service.listAdminDimensions("m-nether").stream().map(MapAdminDTO::id).toList();
        assertEquals(List.of("m-over", "m-nether", "m-end"), ids,
                "同一存档的维度按主世界→下界→末地排列，别的世界不混进来");
    }

    @Test
    void adminDimensionsIncludeDimensionsThatAreNotRenderedYet() {
        save("m-over", "世界", "minecraft:overworld", "archive-a", true, false);
        save("m-nether", "世界 · 下界", "minecraft:nether", "archive-a", false, false);

        List<MapAdminDTO> dimensions = service.listAdminDimensions("m-over");
        assertEquals(2, dimensions.size(), "渲染页要看到还没渲染的维度");
        assertEquals("EMPTY", dimensions.get(1).state(), "未渲染的维度标出状态，管理员据此切过去渲染");
    }

    @Test
    void publicDimensionsHideDimensionsWithoutArtifactsAndArchivedOnes() {
        save("m-over", "世界", "minecraft:overworld", "archive-a", true, false);
        save("m-nether", "世界 · 下界", "minecraft:nether", "archive-a", true, false);
        save("m-end", "世界 · 末地", "minecraft:the_end", "archive-a", false, false);

        assertEquals(List.of("m-over", "m-nether"),
                service.listPublicDimensions("m-over").stream().map(MapSummaryDTO::id).toList(),
                "没渲染完的维度不能出现在展示页的切换器里");

        repo.findById("m-nether").orElseThrow().archive();
        assertEquals(List.of("m-over"),
                service.listPublicDimensions("m-over").stream().map(MapSummaryDTO::id).toList(),
                "归档的维度从展示页消失");
    }

    @Test
    void aLoneMapIsItsOwnSingleDimensionGroup() {
        save("m-single", "单张图", "minecraft:overworld", "archive-single", true, false);
        save("m-other", "另一张图", "minecraft:overworld", "archive-other", true, false);

        assertEquals(List.of("m-single"),
                service.listAdminDimensions("m-single").stream().map(MapAdminDTO::id).toList(),
                "没有同伴的地图自成一组（前端据此不显示切换器）");
        assertTrue(service.listPublicDimensions("m-single").size() == 1);
    }

    /**
     * 同一个 MC 面板实例的各维度，即便**分几次导入**（各自独立的存档包）也要归为一组。
     *
     * <p>这正是线上「渲染页一个维度都切不了」的来源：用户先后导入了实例的三个维度，三次导入的
     * 世界分组标识各不相同，只看分组标识就一个同伴都认不出来。</p>
     */
    @Test
    void dimensionsFromTheSameInstanceGroupEvenWhenImportedSeparately() {
        save("m-over", "生存世界", "minecraft:overworld", "archive-1", true, false, "inst-1");
        save("m-nether", "下界世界", "minecraft:nether", "archive-2", true, false, "inst-1");
        save("m-end", "末地世界", "minecraft:the_end", "archive-3", false, false, "inst-1");
        // 另一个实例的同名维度不能混进来
        save("other-over", "别的实例", "minecraft:overworld", "archive-4", true, false, "inst-2");
        // 手动上传（没有来源实例、存档也各不相干）仍不归组
        save("manual", "手动上传", "minecraft:overworld", "archive-5", true, false);

        assertEquals(List.of("m-over", "m-nether", "m-end"),
                service.listAdminDimensions("m-nether").stream().map(MapAdminDTO::id).toList());
        assertEquals(List.of("other-over"),
                service.listAdminDimensions("other-over").stream().map(MapAdminDTO::id).toList());
        assertEquals(List.of("manual"),
                service.listAdminDimensions("manual").stream().map(MapAdminDTO::id).toList());
    }

    /**
     * 两条关系求的是**连通闭包**：主世界与下界同实例、下界与末地同存档时，三者必须都在一组。
     */
    @Test
    void groupingIsATransitiveClosure() {
        MapProject overworld = save("m-over", "生存世界", "minecraft:overworld", "archive-1", true, false, "inst-1");
        save("m-nether", "下界世界", "minecraft:nether", "archive-2", true, false, "inst-1");
        MapProject end = save("m-end", "末地世界", "minecraft:the_end", "archive-3", true, false);
        // 把末地并进下界所在的世界分组：末地与主世界没有共同线索，靠闭包才连得上
        MapProject nether = repo.findById("m-nether").orElseThrow();
        end.assignWorldGroup(nether.worldGroup());
        repo.save(end);

        assertEquals(List.of("m-over", "m-nether", "m-end"),
                service.listAdminDimensions("m-end").stream().map(MapAdminDTO::id).toList());
        assertEquals(List.of("m-over", "m-nether", "m-end"),
                service.listAdminDimensions(overworld.id()).stream().map(MapAdminDTO::id).toList());
    }

    @Test
    void importAssignedGroupKeepsTheDimensionsTogether() {
        // 实例导入的写法：先建主世界，再把同一组的其它维度并进主世界那张图的分组里
        MapProject overworld = save("m-over", "世界", "minecraft:overworld", "archive-a", true, false);
        MapProject nether = save("m-nether", "世界 · 下界", "minecraft:nether", "archive-other", true, false);
        MapProject end = save("m-end", "世界 · 末地", "minecraft:the_end", "archive-third", true, false);
        for (MapProject project : List.of(nether, end)) {
            project.assignWorldGroup(overworld.worldGroup());
            repo.save(project);
        }

        assertEquals(List.of("m-over", "m-nether", "m-end"),
                service.listAdminDimensions("m-end").stream().map(MapAdminDTO::id).toList(),
                "显式分组优先于按存档派生：各维度用的是不同存档也要归在一组");
    }

    /** 内存仓储（与编排器测试里的同名假件一致：地图数量小，够用即止）。 */
    private static final class InMemoryProjectRepo implements MapProjectRepo {

        private final Map<String, MapProject> projects = new ConcurrentHashMap<>();

        @Override
        public MapProject save(MapProject project) {
            projects.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<MapProject> findById(String mapId) {
            return Optional.ofNullable(projects.get(mapId));
        }

        @Override
        public List<MapProject> findAll() {
            return List.copyOf(projects.values());
        }

        @Override
        public void delete(String mapId) {
            projects.remove(mapId);
        }

        @Override
        public long count() {
            return projects.size();
        }
    }
}
