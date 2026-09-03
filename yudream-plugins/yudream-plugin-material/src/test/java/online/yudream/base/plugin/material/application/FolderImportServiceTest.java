package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderImportServiceTest {
    private FakeFramework framework;
    private MaterialRepository materials;
    private CategoryService categoryService;
    private FolderImportService importService;

    @BeforeEach
    void setUp() {
        FakeDocumentStore docs = new FakeDocumentStore();
        FakeFileStore files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        CategoryRepository categories = new CategoryRepository(docs);
        MaterialService materialService = new MaterialService(materials, new MaterialVersionRepository(docs),
                categories, new MaterialFileStorage(files),
                new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        categoryService = new CategoryService(categories, materials);
        importService = new FolderImportService(materialService, categoryService);
        framework.addPlatformFile("pf-1", "a".getBytes(StandardCharsets.UTF_8), "image/png");
        framework.addPlatformFile("pf-2", "b".getBytes(StandardCharsets.UTF_8), "image/png");
        framework.addPlatformFile("pf-3", "c".getBytes(StandardCharsets.UTF_8), "image/png");
    }

    @Test
    void importCreatesCategoryFromFolderName() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, " 周年庆物料 ", "PUBLIC", List.of("海报"),
                        List.of(new FolderImportCommand.Item("pf-1", "主视觉.png", null),
                                new FolderImportCommand.Item("pf-2", " banner.jpg ", ""))));

        assertEquals(2, result.total());
        assertEquals(2, result.created());
        assertTrue(result.failures().isEmpty());
        assertEquals("周年庆物料", result.categoryName());
        List<CategoryView> all = categoryService.list();
        assertEquals(1, all.size());
        assertEquals("周年庆物料", all.get(0).name());
        assertEquals(2, all.get(0).materials());
        assertTrue(materials.scanAll().stream().allMatch(m -> result.categoryId().equals(m.categoryId())));
        assertTrue(materials.scanAll().stream().allMatch(m -> Material.VISIBILITY_PUBLIC.equals(m.visibility())));
        assertTrue(materials.scanAll().stream().allMatch(m -> m.tags().contains("海报")));
    }

    @Test
    void importReusesExistingCategoryIgnoringCase() {
        CategoryView existing = categoryService.create("设计稿", 0);
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "设计稿", null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.psd", null))));
        assertEquals(existing.id(), result.categoryId());
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void importWithCategoryIdSkipsNameResolution() {
        CategoryView existing = categoryService.create("视频", 0);
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(existing.id(), "会被忽略的名字", null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.mp4", null))));
        assertEquals(existing.id(), result.categoryId());
        assertEquals("视频", result.categoryName());
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void importWithoutCategoryLeavesUncategorized() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, " ", null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.png", null))));
        assertEquals(1, result.created());
        assertEquals("", result.categoryId() == null ? "" : result.categoryId());
        assertTrue(categoryService.list().isEmpty());
    }

    @Test
    void perItemFailureDoesNotAbortBatch() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "混合", null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "ok.png", null),
                                new FolderImportCommand.Item("missing", "bad.png", null),
                                new FolderImportCommand.Item("pf-2", "ok2.png", null))));
        assertEquals(3, result.total());
        assertEquals(2, result.created());
        assertEquals(1, result.failures().size());
        assertEquals("bad.png", result.failures().get(0).filename());
    }

    @Test
    void emptyOrOversizedBatchRejected() {
        assertThrows(IllegalArgumentException.class, () -> importService.importFolder("7",
                new FolderImportCommand(null, "x", null, null, List.of())));
        List<FolderImportCommand.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < FolderImportService.MAX_ITEMS + 1; i++) {
            items.add(new FolderImportCommand.Item("pf-1", "f" + i + ".png", null));
        }
        assertThrows(IllegalArgumentException.class, () -> importService.importFolder("7",
                new FolderImportCommand(null, "x", null, null, items)));
    }

    @Test
    void defaultNameFallsBackToFilenameWithoutExt() {
        importService.importFolder("7", new FolderImportCommand(null, null, null, null,
                List.of(new FolderImportCommand.Item("pf-1", "主视觉.final.png", null))));
        assertEquals("主视觉.final", materials.scanAll().get(0).name());
    }
}
