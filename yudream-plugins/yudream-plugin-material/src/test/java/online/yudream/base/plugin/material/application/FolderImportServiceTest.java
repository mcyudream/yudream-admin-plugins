package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.material.domain.MaterialItemVersion;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.material.infrastructure.FakeFileStore;
import online.yudream.base.plugin.material.infrastructure.FakeFramework;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialItemRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialItemVersionRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderImportServiceTest {
    private FakeFramework framework;
    private FakeDocumentStore docs;
    private MaterialRepository materials;
    private MaterialItemRepository items;
    private MaterialItemVersionRepository itemVersions;
    private CategoryService categoryService;
    private FolderImportService importService;

    @BeforeEach
    void setUp() {
        docs = new FakeDocumentStore();
        FakeFileStore files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        items = new MaterialItemRepository(docs);
        itemVersions = new MaterialItemVersionRepository(docs);
        CategoryRepository categories = new CategoryRepository(docs);
        MaterialFileStorage storage = new MaterialFileStorage(files);
        MaterialService materialService = new MaterialService(materials, new MaterialVersionRepository(docs),
                categories, storage, new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        MaterialItemService itemService = new MaterialItemService(materialService, items, itemVersions, storage,
                new PlatformFileIntake(framework));
        materialService.attachItemCascade(itemService);
        categoryService = new CategoryService(categories, materials);
        importService = new FolderImportService(materialService, itemService, categoryService);
        framework.addPlatformFile("pf-1", "a".getBytes(StandardCharsets.UTF_8), "image/png");
        framework.addPlatformFile("pf-2", "b".getBytes(StandardCharsets.UTF_8), "image/png");
        framework.addPlatformFile("pf-3", "c".getBytes(StandardCharsets.UTF_8), "image/png");
    }

    /** 组合物料形态导入：整批合并为一个组合物料，name 为组合物料名（null 表示回退分类名）。 */
    private FolderImportService.FolderImportResult bundleImport(String categoryName, String name,
                                                                List<FolderImportCommand.Item> items) {
        return importService.importFolder("7", new FolderImportCommand(FolderImportCommand.MODE_BUNDLE, name,
                null, categoryName, null, null, null, items));
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
        // 全部归入根文件夹分类；子文件夹名既进名称（见 nestedFoldersAreKeptInImportedNames），也作为逐文件附加标签与批次标签合并
        assertEquals(1, categoryService.list().size());
        java.util.Map<String, List<String>> materialTags = new java.util.HashMap<>();
        for (Material material : materials.scanAll()) {
            materialTags.put(material.name(), material.tags());
        }
        assertEquals(List.of("活动", "海报", "横版"), materialTags.get("海报-横版-a"));
        assertEquals(List.of("活动", "海报"), materialTags.get("活动-海报-b"));
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

    @Test
    void filesModeStaysDefaultAndCarriesModeInResult() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(null, "设计稿", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "a.png", null, null),
                                new FolderImportCommand.Item("pf-2", "b.png", null, null))));

        assertEquals(FolderImportCommand.MODE_FILES, result.mode());
        assertNull(result.materialId());
        assertNull(result.materialName());
        assertEquals(2, materials.scanAll().size());
        assertTrue(materials.scanAll().stream().allMatch(material -> material.mainFilePresent()
                && material.itemCount() == 0 && material.currentVersion() == 1));
        assertEquals(0, docs.count(MaterialItemRepository.COLLECTION));
    }

    @Test
    void bundleImportCreatesOneBundleMaterialWithItemsNamedByFilename() {
        FolderImportService.FolderImportResult result = bundleImport("明信片", null,
                List.of(new FolderImportCommand.Item("pf-1", "原图.png", null, null),
                        new FolderImportCommand.Item("pf-2", "设计稿.psd", null, null),
                        new FolderImportCommand.Item("pf-3", "成图.png", null, null)));

        assertEquals(FolderImportCommand.MODE_BUNDLE, result.mode());
        assertEquals(3, result.total());
        assertEquals(3, result.created());
        assertTrue(result.failures().isEmpty());
        // 整批只建出一个组合物料：没有主文件（currentVersion = 0），名称缺省取根文件夹名
        List<Material> all = materials.scanAll();
        assertEquals(1, all.size());
        Material bundle = all.get(0);
        assertEquals(result.materialId(), bundle.id());
        assertEquals("明信片", bundle.name());
        assertEquals("明信片", result.materialName());
        assertFalse(bundle.mainFilePresent());
        assertEquals(0, bundle.currentVersion());
        assertEquals(3, bundle.itemCount());
        // 子物料名取文件名去扩展名，各自独立版本链从 v1 起
        assertEquals(List.of("原图", "设计稿", "成图"), items.listByMaterial(bundle.id()).stream()
                .map(MaterialItem::name).toList());
        assertTrue(items.listByMaterial(bundle.id()).stream().allMatch(item -> item.currentVersion() == 1));
        // 分类仍取根文件夹名，且只创建一次
        assertEquals("明信片", result.categoryName());
        assertEquals(1, categoryService.list().size());
        assertEquals(1, categoryService.list().get(0).materials());
    }

    @Test
    void bundleImportKeepsExplicitItemNameAndRecordsSubfolderInVersionNote() {
        FolderImportService.FolderImportResult result = bundleImport("明信片", null,
                List.of(new FolderImportCommand.Item("pf-1", "raw-0001.png", "原图", null),
                        new FolderImportCommand.Item("pf-2", "banner.png", null, List.of("海报", "横版"))));

        assertEquals(2, result.created());
        // 显式名称原样使用；未给名称时目录名按「-」接在文件名前
        assertEquals(List.of("原图", "海报-横版-banner"), items.listByMaterial(result.materialId()).stream()
                .map(MaterialItem::name).toList());
        MaterialItem root = items.listByMaterial(result.materialId()).get(0);
        assertNull(itemVersions.find(root.id(), 1).orElseThrow().note());
        MaterialItem nested = items.listByMaterial(result.materialId()).get(1);
        assertEquals("来源目录：海报/横版", itemVersions.find(nested.id(), 1).orElseThrow().note());
        // 子目录名只进名称与备注：组合物料身上不带子文件夹标签
        assertEquals(List.of(), materials.findById(result.materialId()).orElseThrow().tags());
    }

    @Test
    void bundleImportAppliesBatchTagsAndVisibilityToBundleOnly() {
        FolderImportService.FolderImportResult result = importService.importFolder("7",
                new FolderImportCommand(FolderImportCommand.MODE_BUNDLE, "明信片", null, "明信片", "PUBLIC",
                        null, List.of("活动"), List.of(new FolderImportCommand.Item("pf-1", "原图.png", null,
                                List.of("海报")))));

        Material bundle = materials.findById(result.materialId()).orElseThrow();
        assertEquals(List.of("活动"), bundle.tags());
        assertEquals(Material.VISIBILITY_PUBLIC, bundle.visibility());
    }

    @Test
    void bundleMaterialNameFallsBackToFolderNameThenConstant() {
        assertEquals("品牌Logo", bundleImport("品牌Logo", null,
                List.of(new FolderImportCommand.Item("pf-1", "不透明.jpg", null, null))).materialName());
        assertEquals("未命名组合物料", bundleImport(null, null,
                List.of(new FolderImportCommand.Item("pf-1", "a.png", null, null))).materialName());
    }

    @Test
    void bundleImportPerItemFailureKeepsBundleAndSucceededItems() {
        FolderImportService.FolderImportResult result = bundleImport("明信片", null,
                List.of(new FolderImportCommand.Item("missing", "bad.png", null, null),
                        new FolderImportCommand.Item("pf-1", "原图.png", null, null),
                        new FolderImportCommand.Item("pf-2", "成图.png", null, null)));

        assertEquals(3, result.total());
        assertEquals(2, result.created());
        assertEquals(1, result.failures().size());
        assertEquals("bad.png", result.failures().get(0).filename());
        assertEquals(2, materials.findById(result.materialId()).orElseThrow().itemCount());
        assertEquals(2, items.listByMaterial(result.materialId()).size());
    }

    @Test
    void bundleImportRemovesShellWhenNoItemSucceeded() {
        FolderImportService.FolderImportResult result = bundleImport("空壳", null,
                List.of(new FolderImportCommand.Item("missing", "a.png", null, null),
                        new FolderImportCommand.Item("pf-9", "b.png", null, null)));

        assertEquals(0, result.created());
        assertEquals(2, result.failures().size());
        assertNull(result.materialId());
        assertNull(result.materialName());
        // 一个子物料都没建成时不留空壳组合物料，也不留子物料残留
        assertTrue(materials.scanAll().isEmpty());
        assertEquals(0, docs.count(MaterialItemRepository.COLLECTION));
        assertEquals(0, docs.count(MaterialItemVersionRepository.COLLECTION));
    }

    // ---------- 嵌套目录：物料名/子物料名保留文件夹名 ----------

    @Test
    void nestedFoldersAreKeptInImportedNames() {
        FolderImportService.FolderImportResult files = importService.importFolder("7",
                new FolderImportCommand(null, "宣传物料", null, null, null,
                        List.of(new FolderImportCommand.Item("pf-1", "封面.png", null, List.of("海报", "横版")),
                                new FolderImportCommand.Item("pf-2", "logo.png", null, null),
                                new FolderImportCommand.Item("pf-3", "成图.png", "主视觉", List.of("海报")))));

        assertEquals(3, files.created());
        assertTrue(files.failures().isEmpty());
        List<String> names = materials.scanAll().stream().map(Material::name).toList();
        assertTrue(names.contains("海报-横版-封面"));
        // 根目录直下的文件没有目录前缀；显式名称不加前缀
        assertTrue(names.contains("logo"));
        assertTrue(names.contains("主视觉"));

        FolderImportService.FolderImportResult bundle = bundleImport("明信片", null,
                List.of(new FolderImportCommand.Item("pf-2", "成图.png", null, List.of("海报", "横版")),
                        new FolderImportCommand.Item("pf-3", "原图.png", null, null)));
        assertEquals(List.of("海报-横版-成图", "原图"), items.listByMaterial(bundle.materialId()).stream()
                .map(MaterialItem::name).toList());
    }

    @Test
    void composedNameJoinsNestedFoldersWithDash() {
        assertEquals("海报-横版-封面", FolderImportService.composedName(List.of("海报", "横版"), "封面.png", 120));
        assertEquals("海报-封面", FolderImportService.composedName(List.of("海报"), "封面.png", 120));
        // 无目录 / 目录段全空白时只剩文件名（去扩展名）
        assertEquals("封面", FolderImportService.composedName(null, "封面.png", 120));
        assertEquals("封面", FolderImportService.composedName(List.of(), "封面.png", 120));
        assertEquals("横版-封面", FolderImportService.composedName(List.of(" ", " 横版 "), "封面.png", 120));
        // 多后缀只去最后一段，与单文件上传的取名规则一致
        assertEquals("横版-封面.final", FolderImportService.composedName(List.of("横版"), "封面.final.png", 120));
    }

    /** 超上限时先丢最外层目录（越靠近文件的目录越具体），只剩文件名仍超长才硬截断。 */
    @Test
    void composedNameDropsOutermostFoldersWhenOverLimit() {
        List<String> folders = List.of("一二三四五六七八九十", "ABCDEFGHIJ", "k1k2k3k4k5", "zzzzzzzzzz", "yyyyyyyyyy");
        String name = FolderImportService.composedName(folders, "file-name-here.png", MaterialItemService.MAX_NAME_LENGTH);
        assertEquals("ABCDEFGHIJ-k1k2k3k4k5-zzzzzzzzzz-yyyyyyyyyy-file-name-here", name);
        assertTrue(name.length() <= MaterialItemService.MAX_NAME_LENGTH);
        assertEquals("aaaaaaaaaa", FolderImportService.composedName(folders, "aaaaaaaaaaaaaaaaaaaa.png", 10));
    }

    @Test
    void bundleImportRejectsMoreItemsThanChildLimit() {
        List<FolderImportCommand.Item> items = new ArrayList<>();
        for (int i = 0; i < FolderImportService.MAX_BUNDLE_ITEMS + 1; i++) {
            items.add(new FolderImportCommand.Item("pf-1", "f" + i + ".png", null, null));
        }
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> bundleImport("超量", null, items));
        assertTrue(failure.getMessage().contains(String.valueOf(FolderImportService.MAX_BUNDLE_ITEMS)));
        // 父物料在校验之后才创建，超量时不留下任何记录
        assertTrue(materials.scanAll().isEmpty());
    }
}
