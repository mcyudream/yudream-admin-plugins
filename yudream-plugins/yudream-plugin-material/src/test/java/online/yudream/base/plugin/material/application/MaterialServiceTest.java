package online.yudream.base.plugin.material.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.DeptOption;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialSummary;
import online.yudream.base.plugin.material.application.dto.TagView;
import online.yudream.base.plugin.material.application.dto.VersionView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialVersion;
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
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialServiceTest {
    private FakeDocumentStore docs;
    private FakeFileStore files;
    private FakeFramework framework;
    private MaterialRepository materials;
    private MaterialVersionRepository versions;
    private MaterialService service;

    @BeforeEach
    void setUp() {
        docs = new FakeDocumentStore();
        files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        versions = new MaterialVersionRepository(docs);
        service = new MaterialService(materials, versions,
                new CategoryRepository(docs), new MaterialFileStorage(files),
                new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        framework.addPlatformFile("pf-1", "first-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-2", "second-bytes-v2".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }

    private MaterialDetail createAs(String ownerId, String fileId, String filename, String name) {
        return service.create(ownerId, new CreateMaterialCommand(fileId, filename, name, null, List.of("海报"), null, null));
    }

    @Test
    void createStoresVersionOneAndMaterial() {
        MaterialDetail created = createAs("7", "pf-1", "海报.psd", " 周年海报 ");
        MaterialSummary summary = created.material();
        assertEquals("周年海报", summary.name());
        assertEquals("psd", summary.ext());
        assertEquals("DESIGN", summary.type());
        assertEquals("设计稿", summary.typeLabel());
        assertEquals(1, summary.currentVersion());
        assertEquals("7", summary.ownerId());
        assertEquals("用户7", summary.ownerName());
        // 缺省可见性为仅自己
        assertEquals(Material.VISIBILITY_PRIVATE, summary.visibility());
        assertTrue(files.exists("materials/" + summary.id() + "/v1/file"));

        MaterialDetail detail = service.detailVisible("7", summary.id());
        assertEquals(summary.id(), detail.material().id());
        assertEquals(1, detail.currentVersionInfo().version());
        assertEquals("海报.psd", detail.currentVersionInfo().originalName());
    }

    @Test
    void createWithUnknownPlatformFileRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> createAs("7", "missing", "a.png", "a"));
    }

    @Test
    void ownershipIsolatedAcrossUsers() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "mine");
        String id = created.material().id();
        assertThrows(NotFoundException.class, () -> service.detailVisible("8", id));
        assertThrows(NotFoundException.class,
                () -> service.updateMeta("8", id, new UpdateMaterialCommand("x", null, null, null, null)));
        assertEquals(0, service.listVisible("8", null, null, null, null, null, null, 1, 20).total());
    }

    @Test
    void listVisibleFiltersAndPaginates() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        MaterialDetail second = service.create("7",
                new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, null, null, null));

        assertEquals(2, service.listVisible("7", null, null, null, null, null, null, 1, 20).total());
        assertEquals(1, service.listVisible("7", null, null, "VIDEO", null, null, null, 1, 20).total());
        assertEquals(1, service.listVisible("7", null, "海报", null, null, null, null, 1, 20).total());
        assertEquals(0, service.listVisible("7", null, "不存在", null, null, null, null, 1, 20).total());

        PageResult<MaterialSummary> page = service.listVisible("7", null, null, null, null, null, null, 1, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.records().size());
        // 新创建的排最前（id 倒置时间戳）
        assertEquals(second.material().id(), page.records().get(0).id());
    }

    @Test
    void archivedHiddenByDefault() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "mine");
        String id = created.material().id();
        Material material = materials.findById(id).orElseThrow();
        materials.save(material.withStatus(Material.STATUS_ARCHIVED, System.currentTimeMillis()));

        assertEquals(0, service.listVisible("7", null, null, null, null, null, null, 1, 20).total());
        assertEquals(1, service.listVisible("7", null, null, null, null, "ARCHIVED", null, 1, 20).total());
    }

    @Test
    void listVisibleFiltersByTag() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        service.create("7", new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, List.of("视频", "宣传"), null, null));

        assertEquals(1, service.listVisible("7", null, null, null, null, null, "视频", 1, 20).total());
        assertEquals(1, service.listVisible("7", null, null, null, null, null, "海报", 1, 20).total());
        assertEquals(0, service.listVisible("7", null, null, null, null, null, "不存在", 1, 20).total());
        // 精确匹配（忽略大小写），不做子串匹配
        assertEquals(0, service.listVisible("7", null, null, null, null, null, "视", 1, 20).total());
    }

    @Test
    void listVisibleTagsCountsVisibleActiveMaterials() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        MaterialDetail second = service.create("7",
                new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, List.of("海报", "视频"), null, null));
        framework.addPlatformFile("pf-4", "y".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        // 他人的私有物料不进入标签云
        service.create("8", new CreateMaterialCommand("pf-4", "c.png", "别人的", null, List.of("私有"), null, null));
        framework.addPlatformFile("pf-5", "z".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        // 他人的公开物料进入标签云
        service.create("8", new CreateMaterialCommand("pf-5", "d.png", "公开素材", null, List.of("素材"), "PUBLIC", null));

        List<TagView> tags = service.listVisibleTags("7");
        assertEquals(3, tags.size());
        assertEquals("海报", tags.get(0).name());
        assertEquals(2, tags.get(0).count());
        // 同计数按名称字典序
        assertEquals("素材", tags.get(1).name());
        assertEquals(1, tags.get(1).count());
        assertEquals("视频", tags.get(2).name());
        assertEquals(1, tags.get(2).count());

        // 归档后不再计入标签云
        Material material = materials.findById(second.material().id()).orElseThrow();
        materials.save(material.withStatus(Material.STATUS_ARCHIVED, System.currentTimeMillis()));
        tags = service.listVisibleTags("7");
        assertEquals(2, tags.size());
        assertEquals("海报", tags.get(0).name());
        assertEquals(1, tags.get(0).count());
    }

    // ---------- 可见性授权矩阵 ----------

    @Test
    void publicMaterialReadableByOthersButNotWritable() {
        MaterialDetail created = service.create("7",
                new CreateMaterialCommand("pf-1", "a.png", "公开图", null, null, "PUBLIC", null));
        String id = created.material().id();

        // 他人可见：详情 / 版本 / 列表 / 下载读通路
        assertEquals(id, service.detailVisible("8", id).material().id());
        assertEquals(1, service.listVersions("8", id).size());
        assertEquals(1, service.listVisible("8", null, null, null, null, null, null, 1, 20).total());
        Material material = service.requireVisible("8", id);
        assertEquals(id, material.id());

        // 他人不可写：改名 / 新版本 / 删除仍按归属拦截
        assertThrows(NotFoundException.class,
                () -> service.updateMeta("8", id, new UpdateMaterialCommand("篡改", null, null, null, null)));
        assertThrows(NotFoundException.class,
                () -> service.newVersion("8", id, new NewVersionCommand("pf-2", "b.png", null)));
        assertThrows(NotFoundException.class, () -> service.deleteMine("8", id));

        // scope=mine 只列自己的
        assertEquals(0, service.listVisible("8", "mine", null, null, null, null, null, 1, 20).total());
        assertEquals(1, service.listVisible("7", "mine", null, null, null, null, null, 1, 20).total());
    }

    @Test
    void deptMaterialVisibleWithinSameDeptOnly() {
        framework.setUserDepts(7, List.of(new PluginUserDept(100L, "技术部", true)));
        framework.setUserDepts(8, List.of(new PluginUserDept(100L, "技术部", false)));
        framework.setUserDepts(9, List.of(new PluginUserDept(200L, "市场部", true)));

        MaterialDetail created = service.create("7",
                new CreateMaterialCommand("pf-1", "a.png", "部门图", null, null, "DEPT", List.of("100")));
        String id = created.material().id();
        assertEquals(Material.VISIBILITY_DEPT, created.material().visibility());
        assertEquals(List.of("100"), created.material().deptIds());
        assertEquals(List.of("技术部"), created.material().deptNames());

        // 同部门可见，跨部门不可见
        assertEquals(id, service.detailVisible("8", id).material().id());
        assertThrows(NotFoundException.class, () -> service.detailVisible("9", id));
        assertEquals(1, service.listVisible("8", null, null, null, null, null, null, 1, 20).total());
        assertEquals(0, service.listVisible("9", null, null, null, null, null, null, 1, 20).total());
    }

    @Test
    void deptVisibilityRequiresExplicitDepartments() {
        // DEPT 不带部门 → 报错
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () -> service.create("7",
                new CreateMaterialCommand("pf-1", "a.png", "部门图", null, null, "DEPT", null)));
        assertEquals("可见范围为仅部门时请选择可见部门", empty.getMessage());

        // 选择的部门超出自己所在部门 → 报错
        framework.setUserDepts(7, List.of(new PluginUserDept(100L, "技术部", true)));
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class, () -> service.create("7",
                new CreateMaterialCommand("pf-1", "a.png", "部门图", null, null, "DEPT", List.of("200"))));
        assertEquals("只能选择您所在的部门作为可见范围", foreign.getMessage());
    }

    @Test
    void updateVisibilityRequiresOwnDepartments() {
        framework.setUserDepts(7, List.of(new PluginUserDept(100L, "技术部", true)));
        MaterialDetail created = createAs("7", "pf-1", "a.png", "mine");
        String id = created.material().id();

        MaterialDetail updated = service.updateMeta("7", id,
                new UpdateMaterialCommand(null, null, null, "DEPT", List.of("100")));
        assertEquals(Material.VISIBILITY_DEPT, updated.material().visibility());
        assertEquals(List.of("100"), updated.material().deptIds());
        assertEquals(List.of("技术部"), updated.material().deptNames());

        // visibility=null 不动可见性
        MaterialDetail untouched = service.updateMeta("7", id,
                new UpdateMaterialCommand("改名", null, null, null, null));
        assertEquals(Material.VISIBILITY_DEPT, untouched.material().visibility());
        assertEquals("改名", untouched.material().name());

        // 改回仅自己后部门快照清空
        MaterialDetail back = service.updateMeta("7", id,
                new UpdateMaterialCommand(null, null, null, "private", null));
        assertEquals(Material.VISIBILITY_PRIVATE, back.material().visibility());
        assertTrue(back.material().deptNames().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> service.updateMeta("7", id,
                new UpdateMaterialCommand(null, null, null, "EVERYONE", null)));
    }

    @Test
    void myDepartmentsExposesOnlyOwnDepartments() {
        framework.setUserDepts(7, List.of(new PluginUserDept(100L, "技术部", true)));
        framework.setDeptTree(List.of(
                new PluginDeptOption("100", "技术部", null, "ACTIVE", List.of()),
                new PluginDeptOption("200", "市场部", null, "ACTIVE", List.of())));

        List<DeptOption> own = service.myDepartments("7");
        assertEquals(1, own.size());
        assertEquals("100", own.get(0).id());
        assertEquals("技术部", own.get(0).name());
        // 无部门用户 → 空列表
        assertTrue(service.myDepartments("999").isEmpty());
    }

    @Test
    void legacyDocWithoutVisibilityDefaultsPrivate() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "mine");
        // 模拟 1.1.0 之前的旧文档：剥掉 visibility 字段重写
        var doc = new java.util.HashMap<>(docs.findById(MaterialRepository.COLLECTION, created.material().id()).orElseThrow());
        doc.remove("visibility");
        doc.remove("deptIds");
        doc.remove("deptNames");
        docs.save(MaterialRepository.COLLECTION, created.material().id(), doc);

        Material legacy = materials.findById(created.material().id()).orElseThrow();
        assertEquals(Material.VISIBILITY_PRIVATE, legacy.visibility());
        assertThrows(NotFoundException.class, () -> service.detailVisible("8", legacy.id()));
    }

    @Test
    void newVersionIncrements() {
        MaterialDetail created = createAs("7", "pf-1", "海报.psd", "海报");
        String id = created.material().id();
        MaterialDetail updated = service.newVersion("7", id,
                new NewVersionCommand("pf-2", "海报-v2.psd", "改色"));
        assertEquals(2, updated.material().currentVersion());

        List<VersionView> versions = service.listVersions("7", id);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).version());
        assertTrue(versions.get(0).current());
        assertFalse(versions.get(1).current());
        assertEquals("改色", versions.get(0).note());
        assertTrue(files.exists("materials/" + id + "/v2/file"));
    }

    @Test
    void restoreMovesCurrentPointer() {
        MaterialDetail created = createAs("7", "pf-1", "a.md", "文档");
        String id = created.material().id();
        service.newVersion("7", id, new NewVersionCommand("pf-2", "a.md", null));
        MaterialDetail restored = service.restore("7", id, 1);
        assertEquals(1, restored.material().currentVersion());
        assertEquals(1, service.detailVisible("7", id).currentVersionInfo().version());
    }

    @Test
    void deleteVersionRefusesCurrentAndRemovesObject() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "图");
        String id = created.material().id();
        service.newVersion("7", id, new NewVersionCommand("pf-2", "b.png", null));

        assertThrows(IllegalStateException.class, () -> service.deleteVersion("7", id, 2));
        service.deleteVersion("7", id, 1);
        assertFalse(files.exists("materials/" + id + "/v1/file"));
        assertEquals(1, service.listVersions("7", id).size());
    }

    @Test
    void deleteMineCascades() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "图");
        String id = created.material().id();
        service.newVersion("7", id, new NewVersionCommand("pf-2", "b.png", null));
        service.deleteMine("7", id);

        assertEquals(0, service.listVisible("7", null, null, null, null, null, null, 1, 20).total());
        assertFalse(files.exists("materials/" + id + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/v2/file"));
        assertThrows(NotFoundException.class, () -> service.detailVisible("7", id));
    }

    @Test
    void readBytesOfMissingVersionThrowsNotFound() {
        MaterialDetail created = createAs("7", "pf-1", "a.png", "图");
        Material material = materials.findById(created.material().id()).orElseThrow();
        assertThrows(NotFoundException.class, () -> service.resolveVersion(material, 99));
    }

    @Test
    void sanitizeFilenameStripsPathChars() {
        assertEquals("b.png", MaterialService.sanitizeFilename("a\\b.png"));
        assertEquals("b.png", MaterialService.sanitizeFilename("a/b.png"));
        assertEquals("file", MaterialService.sanitizeFilename("../file"));
        assertThrows(IllegalArgumentException.class, () -> MaterialService.sanitizeFilename("  "));
    }

    @Test
    void createPngStoresCoverJpeg() {
        framework.addPlatformFile("pf-png", samplePng(64, 48), "image/png");
        MaterialDetail created = createAs("7", "pf-png", "a.png", "图");
        String id = created.material().id();
        assertTrue(files.exists("materials/" + id + "/v1/file"));
        assertTrue(files.exists("materials/" + id + "/v1/cover.jpg"));
        MaterialVersion version = versions.find(id, 1).orElseThrow();
        assertEquals("materials/" + id + "/v1/cover.jpg", version.coverObjectKey());
    }

    @Test
    void createPsdDoesNotStoreCover() {
        MaterialDetail created = createAs("7", "pf-1", "海报.psd", "海报");
        String id = created.material().id();
        assertFalse(files.exists("materials/" + id + "/v1/cover.jpg"));
        assertNull(versions.find(id, 1).orElseThrow().coverObjectKey());
    }

    @Test
    void ensureCoverBackfillsLegacyVersion() {
        framework.addPlatformFile("pf-png", samplePng(80, 80), "image/png");
        MaterialDetail created = createAs("7", "pf-png", "a.png", "图");
        String id = created.material().id();
        MaterialVersion original = versions.find(id, 1).orElseThrow();
        files.delete(original.coverObjectKey());
        versions.save(original.withCoverObjectKey(null));
        assertFalse(files.exists("materials/" + id + "/v1/cover.jpg"));

        MaterialVersion filled = service.ensureCover(original.withCoverObjectKey(null));
        assertNotNull(filled.coverObjectKey());
        assertTrue(files.exists(filled.coverObjectKey()));
        assertEquals(filled.coverObjectKey(), versions.find(id, 1).orElseThrow().coverObjectKey());
    }

    @Test
    void deleteMineRemovesCoverObject() {
        framework.addPlatformFile("pf-png", samplePng(32, 32), "image/png");
        MaterialDetail created = createAs("7", "pf-png", "a.png", "图");
        String id = created.material().id();
        service.deleteMine("7", id);
        assertFalse(files.exists("materials/" + id + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/v1/cover.jpg"));
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
