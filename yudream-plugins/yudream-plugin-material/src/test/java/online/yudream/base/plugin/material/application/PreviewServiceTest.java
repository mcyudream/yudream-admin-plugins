package online.yudream.base.plugin.material.application;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.material.application.dto.PreviewInfo;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.FakeFramework;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewFile;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PreviewService 只负责把领域对象翻译成平台契约并透传决策；决策本身由宿主实现与测试覆盖。 */
class PreviewServiceTest {

    /** 可编程平台预览能力桩：记录入参，按预设回应。 */
    private static final class StubPreview implements PluginFilePreviewService {
        String callbackBase = "";
        PluginPreviewInfo nextPreview = PluginPreviewInfo.none("未配置");
        PluginPreviewInfo nextExternal = PluginPreviewInfo.none("未配置");
        String nextSignedUrl = "/api/public/preview/file/tok/file";
        PluginPreviewFile lastFile;
        String lastPluginCode;
        String lastExternalUrl;
        String lastExternalExt;
        long lastExternalSize = -1;

        @Override
        public String callbackBaseUrl() {
            return callbackBase;
        }

        @Override
        public String signedFileUrl(String pluginCode, String objectKey, String filename) {
            lastPluginCode = pluginCode;
            return nextSignedUrl;
        }

        @Override
        public PluginPreviewInfo preview(String pluginCode, PluginPreviewFile file) {
            lastPluginCode = pluginCode;
            lastFile = file;
            return nextPreview;
        }

        @Override
        public PluginPreviewInfo previewExternal(String absoluteFileUrl, String ext, long size) {
            lastExternalUrl = absoluteFileUrl;
            lastExternalExt = ext;
            lastExternalSize = size;
            return nextExternal;
        }
    }

    private FakeFramework framework;
    private StubPreview stub;
    private PreviewService service;

    @BeforeEach
    void setUp() {
        framework = new FakeFramework();
        stub = new StubPreview();
        framework.useFilePreview(stub);
        service = new PreviewService(framework, "material");
    }

    private static Material material(String ext, MaterialType type) {
        long now = System.currentTimeMillis();
        return new Material("m1", "素材." + ext, ext, type, null, List.of(),
                "7", "用户7", 1, 1024L, null, Material.STATUS_ACTIVE, now, now);
    }

    private static MaterialVersion version(Material material) {
        return new MaterialVersion(MaterialVersion.idOf(material.id(), 1), material.id(), 1,
                "materials/m1/v1/file", "素材." + material.ext(), material.ext(), 1024L,
                "image/png", null, "7", "用户7", System.currentTimeMillis());
    }

    private static PluginHttpRequest request(Map<String, List<String>> headers) {
        return new PluginHttpRequest("GET", "/me/materials/m1/preview", headers, Map.of(), null, null);
    }

    @Test
    void previewDelegatesToPlatformAndMapsResult() {
        Material material = material("psd", MaterialType.DESIGN);
        stub.nextPreview = PluginPreviewInfo.kkfile("http://kk:8012/onlinePreview?url=abc");
        PreviewInfo info = service.preview(material, version(material), request(Map.of()));
        assertEquals("KKFILE", info.mode());
        assertEquals("http://kk:8012/onlinePreview?url=abc", info.url());
        assertNull(info.message());
        assertEquals("material", stub.lastPluginCode);
        assertEquals("materials/m1/v1/file", stub.lastFile.objectKey());
        assertEquals("素材.psd", stub.lastFile.filename());
        assertEquals("image/png", stub.lastFile.contentType());
        assertEquals(1024L, stub.lastFile.size());
    }

    @Test
    void previewMapsNoneMessage() {
        Material material = material("psd", MaterialType.DESIGN);
        stub.nextPreview = PluginPreviewInfo.none("平台未提供文件预览能力");
        PreviewInfo info = service.preview(material, version(material), request(Map.of()));
        assertEquals("NONE", info.mode());
        assertNull(info.url());
        assertNotNull(info.message());
    }

    @Test
    void signedFilePathDelegatesToPlatform() {
        Material material = material("png", MaterialType.IMAGE);
        String url = service.signedFilePath(material, version(material));
        assertEquals("/api/public/preview/file/tok/file", url);
        assertEquals("material", stub.lastPluginCode);
    }

    @Test
    void previewSharedUsesConfiguredCallbackBase() {
        stub.callbackBase = "https://host.example.com/";
        stub.nextExternal = PluginPreviewInfo.direct("https://host.example.com/api/plugins/material/public/share/t1/file/素材.png");
        Material material = material("png", MaterialType.IMAGE);
        PreviewInfo info = service.previewShared(material, version(material), "t1", request(Map.of()));
        assertEquals("DIRECT", info.mode());
        // 浏览器直连预览必须回到分享页相对地址，不能把回源绝对地址暴露给访问者浏览器
        assertEquals("t1/file/" + java.net.URLEncoder.encode("素材.png", java.nio.charset.StandardCharsets.UTF_8),
                info.url());
        assertEquals("https://host.example.com/api/plugins/material/public/share/t1/file/"
                + java.net.URLEncoder.encode("素材.png", java.nio.charset.StandardCharsets.UTF_8), stub.lastExternalUrl);
        assertEquals("png", stub.lastExternalExt);
        assertEquals(1024L, stub.lastExternalSize);
    }

    @Test
    void previewSharedDerivesBaseFromRequestHeaders() {
        stub.nextExternal = PluginPreviewInfo.kkfile("http://kk:8012/onlinePreview?url=abc");
        Material material = material("docx", MaterialType.DOCUMENT);
        PreviewInfo info = service.previewShared(material, version(material), "t1", request(Map.of(
                "Host", List.of("admin.example.com"),
                "X-Forwarded-Proto", List.of("https"))));
        assertEquals("KKFILE", info.mode());
        assertTrue(stub.lastExternalUrl.startsWith("https://admin.example.com/api/plugins/material/public/share/t1/file/"),
                stub.lastExternalUrl);
    }

    @Test
    void previewSharedWithoutBaseAndHeadersThrows() {
        Material material = material("docx", MaterialType.DOCUMENT);
        assertThrows(IllegalStateException.class,
                () -> service.previewShared(material, version(material), "t1", request(Map.of())));
    }

    @Test
    void previewSharedFallsBackToVersionExt() {
        stub.callbackBase = "https://host.example.com";
        long now = System.currentTimeMillis();
        Material material = new Material("m1", "素材", "", MaterialType.DOCUMENT, null, List.of(),
                "7", "用户7", 1, 1024L, null, Material.STATUS_ACTIVE, now, now);
        MaterialVersion version = new MaterialVersion(MaterialVersion.idOf("m1", 1), "m1", 1,
                "materials/m1/v1/file", "素材.docx", "docx", 1024L,
                null, null, "7", "用户7", now);
        service.previewShared(material, version, "t1", request(Map.of()));
        assertEquals("docx", stub.lastExternalExt);
    }
}
