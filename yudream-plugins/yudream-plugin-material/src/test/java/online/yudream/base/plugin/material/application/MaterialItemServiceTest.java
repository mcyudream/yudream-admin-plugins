package online.yudream.base.plugin.material.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.CreateMaterialItemCommand;
import online.yudream.base.plugin.material.application.command.NewItemVersionCommand;
import online.yudream.base.plugin.material.application.command.RenameMaterialItemCommand;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialItemDetail;
import online.yudream.base.plugin.material.application.dto.MaterialItemVersionView;
import online.yudream.base.plugin.material.application.dto.MaterialItemView;
import online.yudream.base.plugin.material.domain.Material;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子物料用例：组合物料创建、多子物料并存、各子物料独立版本链、
 * 归属与可见性边界（含管理员走用户端不得越权）、父物料删除级联。
 */
class MaterialItemServiceTest {
    private FakeDocumentStore docs;
    private FakeFileStore files;
    private FakeFramework framework;
    private MaterialRepository materials;
    private MaterialItemRepository items;
    private MaterialItemVersionRepository itemVersions;
    private MaterialService materialService;
    private MaterialItemService service;

    @BeforeEach
    void setUp() {
        docs = new FakeDocumentStore();
        files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        items = new MaterialItemRepository(docs);
        itemVersions = new MaterialItemVersionRepository(docs);
        MaterialFileStorage storage = new MaterialFileStorage(files);
        materialService = new MaterialService(materials, new MaterialVersionRepository(docs),
                new CategoryRepository(docs), storage, new PlatformFileIntake(framework),
                new ShareRepository(docs), framework);
        service = new MaterialItemService(materialService, items, itemVersions, storage,
                new PlatformFileIntake(framework));
        materialService.attachItemCascade(service);
        framework.addPlatformFile("pf-1", "first".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-2", "second-v2".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-3", "third".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }

    /** 创建不带主文件的组合物料，返回其 id。 */
    private String container(String ownerId, String name) {
        MaterialDetail detail = materialService.create(ownerId,
                new CreateMaterialCommand(null, null, name, null, List.of("素材"), "PRIVATE", null));
        return detail.material().id();
    }

    private MaterialItemDetail addItem(String ownerId, String materialId, String fileId, String filename, String name) {
        return service.createItem(ownerId, materialId, new CreateMaterialItemCommand(fileId, filename, name));
    }

    // ---------- 组合物料 ----------

    @Test
    void containerMaterialHasNoMainFileAndNoVersion() {
        MaterialDetail created = materialService.create("7",
                new CreateMaterialCommand(null, null, " 明信片 ", null, List.of("印刷"), "PRIVATE", null));
        assertEquals("明信片", created.material().name());
        assertEquals(0, created.material().currentVersion());
        assertFalse(created.material().mainFilePresent());
        assertEquals(0, created.material().itemCount());
        assertEquals("其他", created.material().typeLabel());
        // 组合物料没有主版本链
        assertNull(created.currentVersionInfo());
        assertEquals(0, materialService.listVersions("7", created.material().id()).size());
    }

    @Test
    void containerRequiresName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> materialService.create("7",
                new CreateMaterialCommand(null, null, "   ", null, null, null, null)));
        assertEquals("组合物料需要填写名称", error.getMessage());
    }

    /** 组合物料后续也可以补上主文件：版本号自然从 v1 开始。 */
    @Test
    void containerCanLaterReceiveMainFile() {
        String id = container("7", "明信片");
        MaterialDetail updated = materialService.newVersion("7", id,
                new online.yudream.base.plugin.material.application.command.NewVersionCommand("pf-1", "主图.png", null));
        assertTrue(updated.material().mainFilePresent());
        assertEquals(1, updated.material().currentVersion());
        assertTrue(files.exists("materials/" + id + "/v1/file"));
    }

    // ---------- 多子物料 + 独立版本链 ----------

    @Test
    void multipleItemsCoexistWithIndependentVersionChains() {
        String id = container("7", "明信片");
        MaterialItemView original = addItem("7", id, "pf-1", "原图.png", "原图").item();
        MaterialItemView design = addItem("7", id, "pf-2", "设计稿.psd", "设计稿").item();
        MaterialItemView finished = addItem("7", id, "pf-3", "成图.png", "成图").item();

        List<MaterialItemView> list = service.listVisible("7", id);
        assertEquals(3, list.size());
        // 按上传顺序返回
        assertEquals(List.of("原图", "设计稿", "成图"), list.stream().map(MaterialItemView::name).toList());
        assertEquals("png", list.get(0).ext());
        assertEquals("IMAGE", list.get(0).type());
        assertEquals("psd", list.get(1).ext());
        assertEquals("DESIGN", list.get(1).type());
        // 父物料上的冗余统计跟随变化
        assertEquals(3, materialService.requireOwn("7", id).itemCount());

        // 只给「设计稿」上传第二版：其余子物料版本链不受影响
        MaterialItemDetail designV2 = service.newVersion("7", id, design.id(),
                new NewItemVersionCommand("pf-1", "设计稿-v2.psd", "改配色"));
        assertEquals(2, designV2.item().currentVersion());
        assertEquals("改配色", designV2.currentVersionInfo().note());
        assertEquals(1, service.detailVisible("7", id, original.id()).item().currentVersion());
        assertEquals(1, service.detailVisible("7", id, finished.id()).item().currentVersion());
        assertEquals(2, service.listVersions("7", id, design.id()).size());
        assertEquals(1, service.listVersions("7", id, original.id()).size());

        assertTrue(files.exists("materials/" + id + "/items/" + design.id() + "/v1/file"));
        assertTrue(files.exists("materials/" + id + "/items/" + design.id() + "/v2/file"));
        assertTrue(files.exists("materials/" + id + "/items/" + original.id() + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/items/" + original.id() + "/v2/file"));
    }

    @Test
    void itemRestoreMovesCurrentPointerAndOldVersionKept() {
        String id = container("7", "logo");
        MaterialItemView jpg = addItem("7", id, "pf-1", "logo.jpg", "非透明背景").item();
        service.newVersion("7", id, jpg.id(), new NewItemVersionCommand("pf-2", "logo-v2.jpg", null));

        MaterialItemDetail restored = service.restoreVersion("7", id, jpg.id(), 1);
        assertEquals(1, restored.item().currentVersion());
        assertEquals(2, service.listVersions("7", id, jpg.id()).size());
        // 版本列表按版本号倒序：v2 在前，回滚后当前指针落在 v1
        List<MaterialItemVersionView> versions = service.listVersions("7", id, jpg.id());
        assertEquals(2, versions.get(0).version());
        assertFalse(versions.get(0).current());
        assertEquals(1, versions.get(1).version());
        assertTrue(versions.get(1).current());
    }

    @Test
    void itemDeleteVersionRefusesCurrentAndRemovesObject() {
        String id = container("7", "logo");
        MaterialItemView jpg = addItem("7", id, "pf-1", "logo.jpg", "非透明背景").item();
        service.newVersion("7", id, jpg.id(), new NewItemVersionCommand("pf-2", "logo-v2.jpg", null));

        assertThrows(IllegalStateException.class, () -> service.deleteVersion("7", id, jpg.id(), 2));
        service.deleteVersion("7", id, jpg.id(), 1);
        assertFalse(files.exists("materials/" + id + "/items/" + jpg.id() + "/v1/file"));
        assertEquals(1, service.listVersions("7", id, jpg.id()).size());
    }

    @Test
    void renameItemKeepsVersionsAndRejectsEmpty() {
        String id = container("7", "logo");
        MaterialItemView png = addItem("7", id, "pf-1", "logo.png", null).item();
        // 名称留空时取文件名去扩展名
        assertEquals("logo", png.name());

        MaterialItemView renamed = service.renameItem("7", id, png.id(), new RenameMaterialItemCommand(" 透明背景 "));
        assertEquals("透明背景", renamed.name());
        assertEquals(1, renamed.currentVersion());
        assertEquals(1, service.listVersions("7", id, png.id()).size());
        assertThrows(IllegalArgumentException.class,
                () -> service.renameItem("7", id, png.id(), new RenameMaterialItemCommand("  ")));
    }

    @Test
    void deleteItemCascadesVersionsAndRefreshesCount() {
        String id = container("7", "明信片");
        MaterialItemView original = addItem("7", id, "pf-1", "原图.png", "原图").item();
        MaterialItemView design = addItem("7", id, "pf-2", "设计稿.psd", "设计稿").item();
        service.newVersion("7", id, original.id(), new NewItemVersionCommand("pf-3", "原图-v2.png", null));

        service.deleteItem("7", id, original.id());
        assertEquals(1, service.listVisible("7", id).size());
        assertEquals(1, materialService.requireOwn("7", id).itemCount());
        // 未受影响的子物料版本链完整保留
        assertEquals(1, service.listVersions("7", id, design.id()).size());
        assertFalse(files.exists("materials/" + id + "/items/" + original.id() + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/items/" + original.id() + "/v2/file"));
        assertThrows(NotFoundException.class, () -> service.detailVisible("7", id, original.id()));
    }

    @Test
    void deletingMaterialCascadesItemsAndVersions() {
        String id = container("7", "明信片");
        MaterialItemView original = addItem("7", id, "pf-1", "原图.png", "原图").item();
        addItem("7", id, "pf-2", "设计稿.psd", "设计稿");
        service.newVersion("7", id, original.id(), new NewItemVersionCommand("pf-3", "原图-v2.png", null));

        materialService.deleteMine("7", id);

        assertThrows(NotFoundException.class, () -> service.listVisible("7", id));
        assertTrue(items.listByMaterial(id).isEmpty());
        assertTrue(itemVersions.listByMaterial(id).isEmpty());
        assertFalse(files.exists("materials/" + id + "/items/" + original.id() + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/items/" + original.id() + "/v2/file"));
    }

    @Test
    void itemCapIsEnforced() {
        String id = container("7", "明信片");
        for (int i = 0; i < MaterialItemService.MAX_ITEMS; i++) {
            framework.addPlatformFile("bulk-" + i, ("x" + i).getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            addItem("7", id, "bulk-" + i, "f" + i + ".png", "子物料" + i);
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> addItem("7", id, "pf-1", "overflow.png", "溢出"));
        assertEquals("单个物料最多上传 " + MaterialItemService.MAX_ITEMS + " 个子物料", error.getMessage());
    }

    // ---------- 归属与可见性 ----------

    @Test
    void itemsFollowParentVisibilityAndOwnership() {
        String id = container("7", "公开组合");

        // 私有组合物料：他人既看不到也写不了
        MaterialItemView privateItem = addItem("7", id, "pf-1", "a.png", "A").item();
        assertThrows(NotFoundException.class, () -> service.listVisible("8", id));
        assertThrows(NotFoundException.class, () -> service.detailVisible("8", id, privateItem.id()));
        assertThrows(NotFoundException.class,
                () -> service.createItem("8", id, new CreateMaterialItemCommand("pf-2", "b.png", "B")));
        assertThrows(NotFoundException.class,
                () -> service.newVersion("8", id, privateItem.id(), new NewItemVersionCommand("pf-2", "a2.png", null)));
        assertThrows(NotFoundException.class, () -> service.deleteItem("8", id, privateItem.id()));
        assertThrows(NotFoundException.class,
                () -> service.renameItem("8", id, privateItem.id(), new RenameMaterialItemCommand("改名")));

        // 改为公开后他可读，但仍不可写（写操作只认属主）
        materialService.updateMeta("7", id,
                new online.yudream.base.plugin.material.application.command.UpdateMaterialCommand(
                        null, null, null, "PUBLIC", null));
        assertEquals(1, service.listVisible("8", id).size());
        assertEquals(privateItem.id(), service.detailVisible("8", id, privateItem.id()).item().id());
        assertThrows(NotFoundException.class,
                () -> service.createItem("8", id, new CreateMaterialItemCommand("pf-2", "b.png", "B")));
        assertThrows(NotFoundException.class, () -> service.deleteItem("8", id, privateItem.id()));

        // scope=mine 语义不受影响：子物料不单独设可见性，随父物料
        assertEquals(1, service.listVersions("8", id, privateItem.id()).size());
    }

    @Test
    void itemMustBelongToTheGivenMaterial() {
        String first = container("7", "A");
        String second = container("7", "B");
        MaterialItemView itemOfFirst = addItem("7", first, "pf-1", "a.png", "A").item();
        assertThrows(NotFoundException.class, () -> service.detailVisible("7", second, itemOfFirst.id()));
        assertThrows(NotFoundException.class, () -> service.requireAnyItem(second, itemOfFirst.id()));
    }

    // ---------- 管理端通道 ----------

    @Test
    void adminPathOperatesAcrossUsersWithoutOwnership() {
        String id = container("7", "明信片");
        MaterialItemDetail created = service.createItemAs("9", id,
                new CreateMaterialItemCommand("pf-1", "原图.png", "原图"));
        // 上传人记操作者，而不是物料属主
        assertEquals("9", created.currentVersionInfo().uploaderId());
        assertEquals("用户9", created.currentVersionInfo().uploaderName());
        assertEquals(1, service.listAsAdmin(id).size());
        assertEquals(1, service.listVersionsAsAdmin(id, created.item().id()).size());

        MaterialItemDetail v2 = service.newVersionAs("9", id, created.item().id(),
                new NewItemVersionCommand("pf-2", "原图-v2.png", "管理员代传"));
        assertEquals(2, v2.item().currentVersion());
        assertEquals(1, service.restoreVersionAs(id, created.item().id(), 1).item().currentVersion());
        service.deleteVersionAs(id, created.item().id(), 2);
        assertEquals(1, service.listVersionsAsAdmin(id, created.item().id()).size());

        service.renameItemAs(id, created.item().id(), new RenameMaterialItemCommand("原图（重命名）"));
        assertEquals("原图（重命名）", service.detailAsAdmin(id, created.item().id()).item().name());

        service.deleteItemAs(id, created.item().id());
        assertTrue(service.listAsAdmin(id).isEmpty());
        assertEquals(0, materialService.requireAny(id).itemCount());
    }

    // ---------- 缩略图 ----------

    @Test
    void imageItemGetsCoverJpegAndPsdDoesNot() {
        framework.addPlatformFile("pf-png", samplePng(64, 48), "image/png");
        String id = container("7", "明信片");
        MaterialItemView image = addItem("7", id, "pf-png", "原图.png", "原图").item();
        MaterialItemView psd = addItem("7", id, "pf-2", "设计稿.psd", "设计稿").item();

        assertTrue(files.exists("materials/" + id + "/items/" + image.id() + "/v1/cover.jpg"));
        assertNotNull(itemVersions.find(image.id(), 1).orElseThrow().coverObjectKey());
        assertFalse(files.exists("materials/" + id + "/items/" + psd.id() + "/v1/cover.jpg"));
        assertNull(itemVersions.find(psd.id(), 1).orElseThrow().coverObjectKey());
    }

    @Test
    void ensureItemCoverBackfillsLegacyVersion() {
        framework.addPlatformFile("pf-png", samplePng(48, 48), "image/png");
        String id = container("7", "明信片");
        MaterialItemView image = addItem("7", id, "pf-png", "原图.png", "原图").item();
        MaterialItemVersion original = itemVersions.find(image.id(), 1).orElseThrow();
        files.delete(original.coverObjectKey());
        itemVersions.save(original.withCoverObjectKey(null));

        MaterialItemVersion filled = service.ensureCover(original.withCoverObjectKey(null));
        assertNotNull(filled.coverObjectKey());
        assertTrue(files.exists(filled.coverObjectKey()));
        assertEquals(filled.coverObjectKey(), itemVersions.find(image.id(), 1).orElseThrow().coverObjectKey());
    }

    @Test
    void itemObjectKeyLayoutIsScopedToParentAndItem() {
        String id = container("7", "明信片");
        MaterialItemView item = addItem("7", id, "pf-1", "原图.png", "原图").item();
        assertEquals("materials/" + id + "/items/" + item.id() + "/v1/file",
                new MaterialFileStorage(files).itemObjectKey(id, item.id(), 1));
        assertEquals(MaterialItemVersion.idOf(item.id(), 1), itemVersions.find(item.id(), 1).orElseThrow().id());
    }

    @Test
    void legacyMaterialDocumentWithoutItemCountReadsZero() {
        MaterialDetail created = materialService.create("7",
                new CreateMaterialCommand("pf-1", "a.png", "普通物料", null, null, null, null));
        var doc = new java.util.HashMap<>(docs.findById(MaterialRepository.COLLECTION, created.material().id()).orElseThrow());
        doc.remove("itemCount");
        docs.save(MaterialRepository.COLLECTION, created.material().id(), doc);

        Material legacy = materials.findById(created.material().id()).orElseThrow();
        assertEquals(0, legacy.itemCount());
        assertTrue(legacy.mainFilePresent());
    }

    // ---------- 预览主文件（组合物料的「主文件」等价物） ----------

    @Test
    void previewTargetFallsBackToFirstItemWhenNotDesignated() {
        String id = container("7", "明信片");
        MaterialItemDetail first = addItem("7", id, "pf-1", "原图.png", "原图");
        addItem("7", id, "pf-2", "成图.png", "成图");

        Material material = materials.findById(id).orElseThrow();
        assertNull(material.previewItemId());
        MaterialItemService.PreviewTarget target = service.resolvePreviewTarget(material);
        assertNotNull(target);
        assertEquals(first.item().id(), target.item().id());
        assertEquals(1, target.version().version());
    }

    @Test
    void previewTargetPrefersDesignatedItem() {
        String id = container("7", "明信片");
        addItem("7", id, "pf-1", "原图.png", "原图");
        MaterialItemDetail third = addItem("7", id, "pf-3", "成图.png", "成图");

        MaterialItemView designated = service.setPreviewItem("7", id, third.item().id());
        assertEquals(third.item().id(), designated.id());
        assertEquals(third.item().id(), materials.findById(id).orElseThrow().previewItemId());
        assertEquals(third.item().id(), service.resolvePreviewTarget(materials.findById(id).orElseThrow()).item().id());
    }

    /** 指针只存子物料 id：子物料升级后父物料预览自动跟随新的当前版本，不会停在旧版本。 */
    @Test
    void previewTargetFollowsItemCurrentVersion() {
        String id = container("7", "明信片");
        MaterialItemDetail item = addItem("7", id, "pf-1", "原图.png", "原图");
        service.setPreviewItem("7", id, item.item().id());
        service.newVersion("7", id, item.item().id(), new NewItemVersionCommand("pf-2", "原图-v2.png", null));

        MaterialItemService.PreviewTarget target = service.resolvePreviewTarget(materials.findById(id).orElseThrow());
        assertEquals(2, target.version().version());
        assertEquals("原图-v2.png", target.version().originalName());
    }

    @Test
    void clearingPreviewItemFallsBackToFirstItem() {
        String id = container("7", "明信片");
        MaterialItemDetail first = addItem("7", id, "pf-1", "原图.png", "原图");
        MaterialItemDetail second = addItem("7", id, "pf-2", "成图.png", "成图");
        service.setPreviewItem("7", id, second.item().id());

        assertNull(service.setPreviewItem("7", id, "  "));
        Material material = materials.findById(id).orElseThrow();
        assertNull(material.previewItemId());
        assertEquals(first.item().id(), service.resolvePreviewTarget(material).item().id());
    }

    /** 删除被指定为预览主文件的子物料时必须清空指针，否则父物料预览会指向已不存在的子物料。 */
    @Test
    void deletingDesignatedItemClearsPreviewItem() {
        String id = container("7", "明信片");
        MaterialItemDetail first = addItem("7", id, "pf-1", "原图.png", "原图");
        MaterialItemDetail second = addItem("7", id, "pf-2", "成图.png", "成图");
        service.setPreviewItem("7", id, second.item().id());

        service.deleteItem("7", id, second.item().id());
        Material material = materials.findById(id).orElseThrow();
        assertNull(material.previewItemId());
        assertEquals(1, material.itemCount());
        assertEquals(first.item().id(), service.resolvePreviewTarget(material).item().id());
    }

    @Test
    void previewItemMustBelongToSameMaterial() {
        String first = container("7", "明信片");
        String other = container("7", "品牌Logo");
        MaterialItemDetail outsider = addItem("7", other, "pf-1", "不透明.jpg", "不透明背景");

        assertThrows(NotFoundException.class, () -> service.setPreviewItem("7", first, outsider.item().id()));
        assertThrows(NotFoundException.class, () -> service.setPreviewItem("9", first, outsider.item().id()));
    }

    @Test
    void previewItemCanBeSetByAdminForOthersMaterial() {
        String id = container("7", "明信片");
        MaterialItemDetail item = addItem("7", id, "pf-1", "原图.png", "原图");

        assertEquals(item.item().id(), service.setPreviewItemAs(id, item.item().id()).id());
        assertEquals(item.item().id(), materials.findById(id).orElseThrow().previewItemId());
    }

    @Test
    void previewTargetNullWhenNoItemExists() {
        String id = container("7", "空组合物料");
        assertNull(service.resolvePreviewTarget(materials.findById(id).orElseThrow()));
    }

    private static byte[] samplePng(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            return buffer.toByteArray();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
