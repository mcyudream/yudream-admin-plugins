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
                new FolderImportCommand(null, " 周年庆物料 ", "PUBLIC", null, List.of("海报"),
                        List.of(new FolderImportCommand.Item("pf-1", "主视觉.png", null, null),
                                new FolderImportCommand.Item("pf-2", " banner.jpg ", "", null))));

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
                new FolderImportCommand(null, "设计稿", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.psd", null, null))));
        assertEquals(existing.id(), result.categoryId());
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void importWithCategoryIdSkipsNameResolution() {
        CategoryView existing = categoryService.create("视频", 0);
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(existing.id(), "会被忽略的名字", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.mp4", null, null))));
        assertEquals(existing.id(), result.categoryId());
        assertEquals("视频", result.categoryName());
        assertEquals(1, categoryService.list().size());
    }

    @Test
    void importWithoutCategoryLeavesUncategorized() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, " ", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.png", null, null))));
        assertEquals(1, result.created());
        assertEquals("", result.categoryId() == null ? "" : result.categoryId());
        assertTrue(categoryService.list().isEmpty());
    }

    @Test
    void perItemFailureDoesNotAbortBatch() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "混合", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "ok.png", null, null),
                                new FolderImportCommand.Item("missing", "bad.png", null, null),
                                new FolderImportCommand.Item("pf-2", "ok2.png", null, null))));
        assertEquals(3, result.total());
        assertEquals(2, result.created());
        assertEquals(1, result.failures().size());
        assertEquals("bad.png", result.failures().get(0).filename());
    }

    @Test
    void emptyOrOversizedBatchRejected() {
        assertThrows(IllegalArgumentException.class, () -> importService.importFolder("7",
                new FolderImportCommand(null, "x", null, null, null, List.of())));
        List<FolderImportCommand.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < FolderImportService.MAX_ITEMS + 1; i++) {
            items.add(new FolderImportCommand.Item("pf-1", "f" + i + ".png", null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> importService.importFolder("7",
                new FolderImportCommand(null, "x", null, null, null, items)));
    }

    @Test
    void defaultNameFallsBackToFilenameWithoutExt() {
        importService.importFolder("7", new FolderImportCommand(null, null, null, null, null,
                List.of(new FolderImportCommand.Item("pf-1", "主视觉.final.png", null, null))));
        assertEquals("主视觉.final", materials.scanAll().get(0).name());
    }

    @Test
    void perItemFolderTagsMergedWithBatchTags() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "周年庆物料", null, null, List.of("活动"),
                        List.of(new FolderImportCommand.Item("pf-1", "a.png", null, List.of("海报", "横版")),
                                new FolderImportCommand.Item("pf-2", "b.png", null, List.of("活动", "海报")),
                                new FolderImportCommand.Item("pf-3", "c.png", null, null))));

        assertEquals(3, result.created());
        assertTrue(result.failures().isEmpty());
        // 全部归入根文件夹分类；子文件夹名作为逐文件附加标签与批次标签合并
        assertEquals(1, categoryService.list().size());
        java.util.Map<String, List<String>> materialTags = new java.util.HashMap<>();
        for (Material material : materials.scanAll()) {
            materialTags.put(material.name(), material.tags());
        }
        assertEquals(List.of("活动", "海报", "横版"), materialTags.get("a"));
        assertEquals(List.of("活动", "海报"), materialTags.get("b"));
        assertEquals(List.of("活动"), materialTags.get("c"));
    }

    @Test
    void perItemTagsCappedAtEightKeepingBatchFirst() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "批次", null, null, List.of("自选一", "自选二"),
                        List.of(new FolderImportCommand.Item("pf-1", "a.png", null,
                                List.of("子1", "子2", "子3", "子4", "子5", "子6", "子7", "子8")))));

        assertEquals(1, result.created());
        assertTrue(result.failures().isEmpty());
        // 批次标签优先保留，子文件夹附加标签补满到上限 8 个
        assertEquals(List.of("自选一", "自选二", "子1", "子2", "子3", "子4", "子5", "子6"),
                materials.scanAll().get(0).tags());
    }

    @Test
    void overlongPerItemTagFailsOnlyThatItem() {
        String tooLong = "这是一个长度超过二十个字符的子文件夹名称标签";
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "批次", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "bad.png", null, List.of(tooLong)),
                                new FolderImportCommand.Item("pf-2", "ok.png", null, List.of("海报")))));

        assertEquals(2, result.total());
        assertEquals(1, result.created());
        assertEquals(1, result.failures().size());
        assertEquals("bad.png", result.failures().get(0).filename());
        assertTrue(result.failures().get(0).message().contains("20"));
        assertEquals(List.of("海报"), materials.scanAll().get(0).tags());
    }
}
