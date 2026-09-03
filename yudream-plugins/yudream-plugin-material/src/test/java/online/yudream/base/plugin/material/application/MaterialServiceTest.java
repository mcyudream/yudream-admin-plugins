package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialServiceTest {
    private FakeDocumentStore docs;
    private FakeFileStore files;
    private FakeFramework framework;
    private MaterialRepository materials;
    private MaterialService service;

    @BeforeEach
    void setUp() {
        docs = new FakeDocumentStore();
        files = new FakeFileStore();
        framework = new FakeFramework();
        materials = new MaterialRepository(docs);
        service = new MaterialService(materials, new MaterialVersionRepository(docs),
                new CategoryRepository(docs), new MaterialFileStorage(files),
                new PlatformFileIntake(framework), new ShareRepository(docs), framework);
        framework.addPlatformFile("pf-1", "first-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        framework.addPlatformFile("pf-2", "second-bytes-v2".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }

    private MaterialDetail createAs(String ownerId, String fileId, String filename, String name) {
        return service.create(ownerId, new CreateMaterialCommand(fileId, filename, name, null, List.of("海报")));
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
        assertTrue(files.exists("materials/" + summary.id() + "/v1/file"));

        MaterialDetail detail = service.detailMine("7", summary.id());
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
        assertThrows(NotFoundException.class, () -> service.detailMine("8", id));
        assertThrows(NotFoundException.class,
                () -> service.updateMeta("8", id, new UpdateMaterialCommand("x", null, null)));
        assertEquals(0, service.listMine("8", null, null, null, null, null, 1, 20).total());
    }

    @Test
    void listMineFiltersAndPaginates() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        MaterialDetail second = service.create("7",
                new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, null));

        assertEquals(2, service.listMine("7", null, null, null, null, null, 1, 20).total());
        assertEquals(1, service.listMine("7", null, "VIDEO", null, null, null, 1, 20).total());
        assertEquals(1, service.listMine("7", "海报", null, null, null, null, 1, 20).total());
        assertEquals(0, service.listMine("7", "不存在", null, null, null, null, 1, 20).total());

        PageResult<MaterialSummary> page = service.listMine("7", null, null, null, null, null, 1, 1);
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

        assertEquals(0, service.listMine("7", null, null, null, null, null, 1, 20).total());
        assertEquals(1, service.listMine("7", null, null, null, "ARCHIVED", null, 1, 20).total());
    }

    @Test
    void listMineFiltersByTag() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        service.create("7", new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, List.of("视频", "宣传")));

        assertEquals(1, service.listMine("7", null, null, null, null, "视频", 1, 20).total());
        assertEquals(1, service.listMine("7", null, null, null, null, "海报", 1, 20).total());
        assertEquals(0, service.listMine("7", null, null, null, null, "不存在", 1, 20).total());
        // 精确匹配（忽略大小写），不做子串匹配
        assertEquals(0, service.listMine("7", null, null, null, null, "视", 1, 20).total());
    }

    @Test
    void listMyTagsCountsOwnActiveMaterials() {
        createAs("7", "pf-1", "a.psd", "设计稿");
        framework.addPlatformFile("pf-3", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        MaterialDetail second = service.create("7",
                new CreateMaterialCommand("pf-3", "b.mp4", "宣传视频", null, List.of("海报", "视频")));
        framework.addPlatformFile("pf-4", "y".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        service.create("8", new CreateMaterialCommand("pf-4", "c.png", "别人的", null, List.of("私有")));

        List<TagView> tags = service.listMyTags("7");
        assertEquals(2, tags.size());
        assertEquals("海报", tags.get(0).name());
        assertEquals(2, tags.get(0).count());
        assertEquals("视频", tags.get(1).name());
        assertEquals(1, tags.get(1).count());

        // 归档后不再计入标签云
        Material material = materials.findById(second.material().id()).orElseThrow();
        materials.save(material.withStatus(Material.STATUS_ARCHIVED, System.currentTimeMillis()));
        tags = service.listMyTags("7");
        assertEquals(1, tags.size());
        assertEquals("海报", tags.get(0).name());
        assertEquals(1, tags.get(0).count());
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
        assertEquals(1, service.detailMine("7", id).currentVersionInfo().version());
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

        assertEquals(0, service.listMine("7", null, null, null, null, null, 1, 20).total());
        assertFalse(files.exists("materials/" + id + "/v1/file"));
        assertFalse(files.exists("materials/" + id + "/v2/file"));
        assertThrows(NotFoundException.class, () -> service.detailMine("7", id));
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
}
