package online.yudream.base.plugin.material.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.ShareView;
import online.yudream.base.plugin.material.domain.MaterialShare;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareServiceTest {
    private MaterialService materialService;
    private ShareRepository shares;
    private ShareService service;

    @BeforeEach
    void setUp() {
        FakeDocumentStore docs = new FakeDocumentStore();
        FakeFileStore files = new FakeFileStore();
        FakeFramework framework = new FakeFramework();
        framework.addPlatformFile("pf-1", "first-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        shares = new ShareRepository(docs);
        materialService = new MaterialService(new MaterialRepository(docs), new MaterialVersionRepository(docs),
                new CategoryRepository(docs), new MaterialFileStorage(files),
                new PlatformFileIntake(framework), shares, framework);
        service = new ShareService(shares, materialService, framework);
    }

    private String createMaterial(String ownerId) {
        return materialService.create(ownerId, new CreateMaterialCommand("pf-1", "海报.psd", "海报", null, List.of(), null))
                .material().id();
    }

    @Test
    void createPermanentShareResolves() {
        String materialId = createMaterial("7");
        ShareView view = service.create("7", materialId, null, " 给设计部用 ");

        assertEquals(0, view.expiresAt());
        assertFalse(view.expired());
        assertEquals("给设计部用", view.note());
        assertEquals("/public/share/" + view.id(), view.url());
        assertEquals("用户7", view.createdByName());
        assertEquals(materialId, service.resolveValid(view.id()).id());
    }

    @Test
    void createValidatesHoursAndNote() {
        String materialId = createMaterial("7");
        assertThrows(IllegalArgumentException.class, () -> service.create("7", materialId, 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.create("7", materialId, 8761, null));
        assertThrows(IllegalArgumentException.class, () -> service.create("7", materialId, 24, "x".repeat(101)));
    }

    @Test
    void createRequiresOwnership() {
        String materialId = createMaterial("7");
        assertThrows(NotFoundException.class, () -> service.create("8", materialId, null, null));
        assertThrows(NotFoundException.class, () -> service.list("8", materialId));
    }

    @Test
    void listReturnsNewestFirstOnlyOfMaterial() {
        String materialId = createMaterial("7");
        String otherId = createMaterial("7");
        ShareView first = service.create("7", materialId, null, null);
        service.create("7", otherId, null, null);
        ShareView latest = service.create("7", materialId, 24, null);

        List<ShareView> records = service.list("7", materialId);
        assertEquals(2, records.size());
        assertEquals(latest.id(), records.get(0).id());
        assertEquals(first.id(), records.get(1).id());
        assertTrue(records.get(0).expiresAt() > 0);
        assertNotEquals(first.id(), latest.id());
    }

    @Test
    void revokeRemovesShareAndMismatchRejected() {
        String materialId = createMaterial("7");
        String otherId = createMaterial("7");
        ShareView view = service.create("7", materialId, null, null);

        assertThrows(NotFoundException.class, () -> service.revoke("7", otherId, view.id()));
        assertThrows(NotFoundException.class, () -> service.revoke("8", materialId, view.id()));

        service.revoke("7", materialId, view.id());
        assertThrows(NotFoundException.class, () -> service.resolveValid(view.id()));
        assertTrue(service.list("7", materialId).isEmpty());
    }

    @Test
    void expiredTokenRejected() {
        long now = System.currentTimeMillis();
        assertTrue(new MaterialShare("t1", "m", "7", null, null, now - 1, now - 1000).expired(now));
        assertFalse(new MaterialShare("t2", "m", "7", null, null, now + 1000, now).expired(now));
        assertFalse(new MaterialShare("t3", "m", "7", null, null, 0, now).expired(now));
    }

    @Test
    void resolveRejectsUnknownTokenAndDeletedMaterial() {
        assertThrows(NotFoundException.class, () -> service.resolveValid("not-exist"));
        assertThrows(NotFoundException.class, () -> service.resolveValid(null));

        String materialId = createMaterial("7");
        ShareView view = service.create("7", materialId, null, null);
        materialService.deleteMine("7", materialId);
        assertThrows(NotFoundException.class, () -> service.resolveValid(view.id()));
        assertTrue(shares.listByMaterial(materialId).isEmpty());
    }
}
