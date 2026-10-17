package online.yudream.plugin.voxelithmap.domain.aggregate;

import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapProject} 的两个「减少对象存储压力」字段的领域约束：
 * 封面版本号（取代每次列表去查一次存储的 hasCover）与模组包（取代逐个 jar 落库）。
 */
class MapProjectStorageFieldsTest {

    private static MapProject newProject() {
        return MapProject.create("测试-1", "测试地图", "world", "worldFileId",
                "", "", List.of(), 0, 64, 0, RenderSettings.defaults());
    }

    /** 新地图没有封面：版本号 0（列表据此判定无需去存储查封面）。 */
    @Test
    void newProjectHasNoCover() {
        assertEquals(0L, newProject().coverRevision());
    }

    /** 保存封面写入的是单调递增的版本号（时间戳），列表缓存指纹据此变化。 */
    @Test
    void coverRevisionAdvancesOnSave() throws InterruptedException {
        MapProject project = newProject();
        project.setCoverRevision(1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, project.coverRevision());
        project.setCoverRevision(1_700_000_000_001L);
        assertEquals(1_700_000_000_001L, project.coverRevision());
    }

    /** 老文档没这个字段：重建后必须是「未解析」哨兵，才能触发一次性迁移而不是被当成「没有封面」。 */
    @Test
    void restoreWithoutFieldMarksUnresolved() {
        MapProject restored = MapProject.restore("老图", "老地图", "world", "worldFileId", "", "",
                null, null, List.of(), "minecraft:overworld", 0, 64, 0, RenderSettings.defaults(),
                null, null, online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState.EMPTY, null,
                List.of(), online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary.empty(),
                null, false, null, MapProject.UNRESOLVED_COVER_REVISION,
                null, java.time.Instant.now(), java.time.Instant.now());
        assertEquals(MapProject.UNRESOLVED_COVER_REVISION, restored.coverRevision());
    }

    /**
     * 世界分组：没显式分组时按**存档**派生（同一份存档建出的多张图＝同一世界的不同维度），
     * 显式指定时以指定值为准且之后不变。
     *
     * <p>分组决定「渲染页/展示页能切到哪些维度」，两条不变量都要钉住：派生规则一旦变了，
     * 同一份存档的图就会互相认不出来；显式值一旦被覆盖，实例导入的多张图就会散开。</p>
     */
    @Test
    void worldGroupDerivesFromArchiveAndCanBeAssigned() {
        MapProject standalone = newProject();
        assertEquals("archive:worldFileId", standalone.worldGroup(), "同存档的地图归为一组");

        // 同一份存档建出的另一张（例如手动上传后分别建主世界与下界）：自动同组
        MapProject nether = MapProject.create("测试-2", "测试地图 · 下界", "world_nether", "worldFileId",
                "", "", List.of(), 0, 64, 0, RenderSettings.defaults());
        assertEquals(standalone.worldGroup(), nether.worldGroup());

        // 没有存档信息时退回自身 id（自成一组，不会误并到别人组里）
        MapProject noArchive = MapProject.create("测试-3", "无存档", "world", "",
                "", "", List.of(), 0, 64, 0, RenderSettings.defaults());
        assertEquals("测试-3", noArchive.worldGroup());

        // 实例导入显式指定分组：以指定值为准，且换存档（同步）也不变
        MapProject imported = MapProject.create("测试-4", "导入的下界", "world_nether", "另一个存档",
                "", "", List.of(), 0, 64, 0, RenderSettings.defaults());
        imported.assignWorldGroup("导入分组");
        assertEquals("导入分组", imported.worldGroup());

        // 老文档缺分组字段：按同一规则兜底
        MapProject legacy = MapProject.restore("老图", "老地图", "world", "worldFileId-1", "", "",
                null, null, List.of(), "minecraft:overworld", 0, 64, 0, RenderSettings.defaults(),
                null, null, online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState.EMPTY, null,
                List.of(), online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary.empty(),
                null, false, null, 0L, null,
                java.time.Instant.now(), java.time.Instant.now());
        assertEquals("archive:worldFileId-1", legacy.worldGroup());
    }

    /** 模组包：设置后可按 id 读回，空值即清除（清空模组=恢复纯原版）。 */
    @Test
    void modsPackSetAndClear() {
        MapProject project = newProject();
        assertTrue(project.modsPackFileId().isEmpty());
        project.setModsPack("file-1", "voxelith-mods.zip");
        assertEquals("file-1", project.modsPackFileId().orElseThrow());
        assertEquals("voxelith-mods.zip", project.modsPackName());
        project.setModsPack("", "");
        assertTrue(project.modsPackFileId().isEmpty(), "空 fileId 应清除模组包");
    }

    /** 模组包与逐个 jar 并存：两者是独立的叠加来源，互不覆盖。 */
    @Test
    void modsPackCoexistsWithIndividualJars() {
        MapProject project = newProject();
        project.setModsPack("pack-1", "mods.zip");
        project.setModJars(List.of(new online.yudream.plugin.voxelithmap.domain.value.ModJarRef("jar-1", "a.jar")));
        assertTrue(project.modsPackFileId().isPresent());
        assertEquals(1, project.modJars().size());
        assertFalse(project.modsPackName().isBlank());
    }
}
