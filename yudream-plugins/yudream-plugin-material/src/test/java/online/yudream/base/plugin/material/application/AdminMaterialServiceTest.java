package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import online.yudream.base.plugin.material.application.AdminMaterialService.BatchResult;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.DeptOption;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.material.infrastructure.FakeFileStore;
import online.yudream.base.plugin.material.infrastructure.FakeFramework;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminMaterialServiceTest {
    private FakeDocumentStore docs;
    private FakeFileStore files;
    private FakeFramework framework;
    private MaterialRepository materials;
    private MaterialService materialService;
    private CategoryService categoryService;
    private AdminMaterialService adminService;

    @BeforeEach
    void setUp() {
        docs = new FakeDocumentStore();
        files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        CategoryRepository categories = new CategoryRepository(docs);
        materialService = new MaterialService(materials, new MaterialVersionRepository(docs),
                categories, new MaterialFileStorage(files),
                new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        categoryService = new CategoryService(categories, materials);
        adminService = new AdminMaterialService(materialService, materials);
        framework.addPlatformFile("pf-1", "first-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-2", "second-bytes-v2".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-3", "third".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.setDeptTree(List.of(
                new PluginDeptOption("100", "技术部", null, "ACTIVE", List.of(
                        new PluginDeptOption("101", "平台组", "100", "ACTIVE", List.of()))),
                new PluginDeptOption("200", "市场部", null, "ACTIVE", List.of())));
    }

    private String createAs(String ownerId, String name) {
        return materialService.create(ownerId,
                new CreateMaterialCommand("pf-1", "a.png", name, null, null, null, null)).material().id();
    }

    // ---------- 部门选项与可见性解析 ----------

    @Test
    void departmentOptionsFlattensTreeWithParentLabels() {
        List<DeptOption> options = adminService.departmentOptions(null);
        assertEquals(3, options.size());
        assertEquals("技术部", options.get(0).label());
        assertEquals("技术部 / 平台组", options.get(1).label());
        assertEquals("平台组", options.get(1).name());
        assertEquals("市场部", options.get(2).label());
    }

    @Test
    void adminUpdateAllowsDeptVisibilityAgainstFullTree() {
        String id = createAs("7", "部门图");
        MaterialDetail updated = adminService.update(id,
                new UpdateMaterialCommand("改名", null, null, "DEPT", List.of("101", "200")));
        assertEquals(Material.VISIBILITY_DEPT, updated.material().visibility());
        assertEquals(List.of("101", "200"), updated.material().deptIds());
        assertEquals(List.of("平台组", "市场部"), updated.material().deptNames());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> adminService.update(id, new UpdateMaterialCommand(null, null, null, "DEPT", List.of("999"))));
        assertEquals("部门不存在或已被删除", unknown.getMessage());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> adminService.update(id, new UpdateMaterialCommand(null, null, null, "DEPT", List.of())));
        assertEquals("可见范围为仅部门时请选择可见部门", empty.getMessage());
    }

    @Test
    void adminUpdateIgnoresOwnership() {
        String id = createAs("7", "他人的");
        MaterialDetail updated = adminService.update(id, new UpdateMaterialCommand("管理员改名", null, null, null, null));
        assertEquals("管理员改名", updated.material().name());
        assertEquals("7", updated.material().ownerId());
    }

    @Test
    void adminNewVersionRecordsOperatorAsUploader() {
        String id = createAs("7", "海报");
        MaterialDetail updated = adminService.newVersion("9", id, new NewVersionCommand("pf-2", "b.png", "代传"));
        assertEquals(2, updated.material().currentVersion());
        assertEquals("用户9", updated.currentVersionInfo().uploaderName());
    }

    // ---------- 批量操作 ----------

    @Test
    void batchCategoryMovesAndCollectsFailures() {
        String first = createAs("7", "一");
        String second = createAs("8", "二");
        var category = categoryService.create("设计稿", 0);

        BatchResult result = adminService.batchCategory(List.of(first, "missing", second), category.id());
        assertEquals(3, result.total());
        assertEquals(2, result.succeeded());
        assertEquals(1, result.failures().size());
        assertEquals("missing", result.failures().get(0).id());
        assertEquals(category.id(), materials.findById(first).orElseThrow().categoryId());
        assertEquals(category.id(), materials.findById(second).orElseThrow().categoryId());

        // 分类不存在 → 整批拒绝，一项都不动
        assertThrows(IllegalArgumentException.class,
                () -> adminService.batchCategory(List.of(first), "not-exist"));
        assertEquals(category.id(), materials.findById(first).orElseThrow().categoryId());

        // 空 categoryId = 移出分类
        BatchResult cleared = adminService.batchCategory(List.of(first), "");
        assertEquals(1, cleared.succeeded());
        assertEquals(null, materials.findById(first).orElseThrow().categoryId());
    }

    @Test
    void batchTagsAppendMergesAndCapsAtEight() {
        String id = createAs("7", "图");
        adminService.batchTags(List.of(id), List.of("a", "b", "c", "d", "e", "f"), "REPLACE");
        BatchResult result = adminService.batchTags(List.of(id), List.of("g", "h", "i"), "APPEND");
        // a-h 共 8 个已满，追加 i 后超限 → 该项失败
        assertEquals(0, result.succeeded());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).message().contains("8"));

        BatchResult ok = adminService.batchTags(List.of(id), List.of("g", "h", "a"), "APPEND");
        assertEquals(1, ok.succeeded());
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h"), materials.findById(id).orElseThrow().tags());

        assertThrows(IllegalArgumentException.class, () -> adminService.batchTags(List.of(id), List.of(), "REPLACE"));
        BatchResult replaced = adminService.batchTags(List.of(id), List.of("新标签"), "REPLACE");
        assertEquals(1, replaced.succeeded());
        assertEquals(List.of("新标签"), materials.findById(id).orElseThrow().tags());
    }

    @Test
    void batchStatusArchivesAndRestores() {
        String first = createAs("7", "一");
        String second = createAs("8", "二");
        BatchResult archived = adminService.batchStatus(List.of(first, second), "ARCHIVED");
        assertEquals(2, archived.succeeded());
        assertEquals(Material.STATUS_ARCHIVED, materials.findById(first).orElseThrow().status());

        BatchResult restored = adminService.batchStatus(List.of(first), "ACTIVE");
        assertEquals(1, restored.succeeded());
        assertEquals(Material.STATUS_ACTIVE, materials.findById(first).orElseThrow().status());

        assertThrows(IllegalArgumentException.class, () -> adminService.batchStatus(List.of(first), "DELETED"));
    }

    @Test
    void batchDeleteCascadesAndValidatesIds() {
        String first = createAs("7", "一");
        materialService.newVersion("7", first, new NewVersionCommand("pf-2", "b.png", null));
        String second = createAs("8", "二");

        assertThrows(IllegalArgumentException.class, () -> adminService.batchDelete(List.of()));
        assertThrows(IllegalArgumentException.class, () -> adminService.batchDelete(List.of(" ", "")));

        BatchResult result = adminService.batchDelete(List.of(first, "missing", second));
        assertEquals(3, result.total());
        assertEquals(2, result.succeeded());
        assertEquals(1, result.failures().size());
        assertFalse(materials.findById(first).isPresent());
        assertFalse(files.exists("materials/" + first + "/v1/file"));
        assertFalse(files.exists("materials/" + first + "/v2/file"));
    }

    @Test
    void batchRejectsOversizedSelection() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < AdminMaterialService.MAX_BATCH + 1; i++) {
            ids.add("id-" + i);
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adminService.batchStatus(ids, "ARCHIVED"));
        assertTrue(error.getMessage().contains("200"));
    }
}
