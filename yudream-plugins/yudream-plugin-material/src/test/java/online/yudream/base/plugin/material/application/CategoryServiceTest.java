package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.material.infrastructure.FakeFileStore;
import online.yudream.base.plugin.material.infrastructure.FakeFramework;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类维护：重点是上传时「快捷新增分类」所依赖的 findOrCreateByName——
 * 任何一个能使用物料库的人都会调它，所以既要能新建，也要在同名时不重复建、且回传真实物料数。
 */
class CategoryServiceTest {
    private FakeFramework framework;
    private MaterialRepository materials;
    private MaterialService materialService;
    private CategoryService categoryService;
    private int platformFileSeq;

    @BeforeEach
    void setUp() {
        FakeDocumentStore docs = new FakeDocumentStore();
        FakeFileStore files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        CategoryRepository categories = new CategoryRepository(docs);
        materialService = new MaterialService(materials, new MaterialVersionRepository(docs), categories,
                new MaterialFileStorage(files), new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        categoryService = new CategoryService(categories, materials);
    }

    /** 造一条归属某分类的物料，用来验证分类视图里的物料数不是写死的 0。 */
    private void createMaterialIn(String categoryId, String name) {
        String fileId = "pf-" + (++platformFileSeq);
        framework.addPlatformFile(fileId, name.getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        materialService.create("7", new CreateMaterialCommand(fileId, name + ".psd", name, categoryId, null, null, null));
    }

    @Test
    void quickAddCreatesCategoryWithAppendedSort() {
        categoryService.create("已有分类", 3);

        CategoryView created = categoryService.findOrCreateByName(" 周年庆 ");

        assertEquals("周年庆", created.name());
        assertEquals(4, created.sort());
        assertEquals(0, created.materials());
        List<CategoryView> all = categoryService.list();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(category -> created.id().equals(category.id())));
    }

    @Test
    void quickAddReusesExistingCategoryIgnoringCase() {
        CategoryView existing = categoryService.create("海报", 0);
        createMaterialIn(existing.id(), "a");
        createMaterialIn(existing.id(), "b");

        CategoryView found = categoryService.findOrCreateByName(" 海报 ");

        assertEquals(existing.id(), found.id());
        assertEquals("海报", found.name());
        // 复用分支要带回真实物料数，而不是写死的 0
        assertEquals(2, found.materials());
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void quickAddRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> categoryService.findOrCreateByName("   "));
        assertThrows(IllegalArgumentException.class, () -> categoryService.findOrCreateByName(null));
        assertTrue(categoryService.list().isEmpty());
    }

    @Test
    void quickAddRejectsOverlongName() {
        assertThrows(IllegalArgumentException.class, () -> categoryService.findOrCreateByName("分".repeat(31)));
        assertTrue(categoryService.list().isEmpty());
    }

    @Test
    void updateAndDeleteReportReferencedMaterialCount() {
        CategoryView category = categoryService.create("海报", 0);
        createMaterialIn(category.id(), "a");

        assertEquals(1, categoryService.update(category.id(), "海报 v2", 5).materials());
        // 名下还有物料时删除要被拒绝，避免物料指向不存在的分类
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> categoryService.delete(category.id()));
        assertTrue(error.getMessage().contains("1"));
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void deleteRemovesUnreferencedCategory() {
        CategoryView category = categoryService.create("空分类", 0);
        categoryService.delete(category.id());
        assertTrue(categoryService.list().isEmpty());
        assertNotEquals(category.id(), categoryService.findOrCreateByName("空分类").id());
    }

    /** 组合物料（无主文件）也应计入分类物料数——它同样是这个分类下的一条物料。 */
    @Test
    void containerMaterialCountsTowardCategory() {
        CategoryView category = categoryService.create("组合", 0);
        materialService.create("7", new CreateMaterialCommand("", "", "组合物料", category.id(), null, null, null));

        assertEquals(1, categoryService.findOrCreateByName("组合").materials());
        assertEquals(1, categoryService.list().get(0).materials());
    }

    /** 未分类（categoryId 为 null）的物料不应被算进任何分类。 */
    @Test
    void uncategorizedMaterialIsNotCounted() {
        String fileId = "pf-uncat";
        framework.addPlatformFile(fileId, "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        materialService.create("7", new CreateMaterialCommand(fileId, "x.psd", "x", null, null, null, null));

        assertEquals(0, categoryService.create("新分类", 0).materials());
        assertEquals(0, categoryService.findOrCreateByName("新分类").materials());
    }

    /** 分类视图里的 createdAt 来自落库时间，快捷新增后立即可读。 */
    @Test
    void quickAddReturnsPersistedCategory() {
        CategoryView created = categoryService.findOrCreateByName("快捷键建的分类");
        assertTrue(created.createdAt() > 0);
        assertTrue(materials.scanAll().isEmpty());
    }
}
