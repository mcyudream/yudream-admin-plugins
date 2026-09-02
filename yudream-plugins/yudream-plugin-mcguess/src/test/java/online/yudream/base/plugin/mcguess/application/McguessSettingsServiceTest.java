package online.yudream.base.plugin.mcguess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.mcguess.application.dto.McguessSettingsView;
import online.yudream.base.plugin.mcguess.domain.McguessSettingsRepository;
import online.yudream.base.plugin.mcguess.infrastructure.StubMcWikiApi;
import org.junit.jupiter.api.Test;

/** 数据版本设置：跟随默认、钉住、取消发布降级、保存校验与 provider 不可用降级。 */
class McguessSettingsServiceTest {

    /** 内存设置仓储。 */
    private static final class InMemorySettings implements McguessSettingsRepository {
        private String gameVersion = "";

        @Override
        public String gameVersion() { return gameVersion; }

        @Override
        public void saveGameVersion(String version) { gameVersion = version == null ? "" : version; }
    }

    private static StubMcWikiApi published(String defaultVersion, List<String> all) {
        StubMcWikiApi api = new StubMcWikiApi();
        api.published = defaultVersion;
        api.publishedList = all;
        return api;
    }

    private static McguessSettingsService service(Optional<StubMcWikiApi> api, InMemorySettings settings) {
        return new McguessSettingsService(() -> api.map(a -> (online.yudream.base.plugin.mcwiki.api.McWikiApi) a), settings);
    }

    @Test
    void followsDefaultWhenNotPinned() {
        StubMcWikiApi api = published("1.21", List.of("1.20.6", "1.21"));
        McguessSettingsService service = service(Optional.of(api), new InMemorySettings());
        assertEquals(Optional.of("1.21"), service.effectiveVersion());
        McguessSettingsView view = service.view();
        assertEquals("", view.gameVersion());
        assertEquals("1.21", view.effectiveVersion());
        assertEquals("1.21", view.defaultVersion());
        assertEquals(List.of("1.20.6", "1.21"), view.publishedVersions());
        assertFalse(view.pinnedMissing());
        assertTrue(view.providerAvailable());
    }

    @Test
    void pinnedPublishedVersionWinsOverDefault() {
        StubMcWikiApi api = published("1.21", List.of("1.20.6", "1.21"));
        InMemorySettings settings = new InMemorySettings();
        McguessSettingsService service = service(Optional.of(api), settings);
        McguessSettingsView view = service.save("1.20.6");
        assertEquals("1.20.6", view.gameVersion());
        assertEquals("1.20.6", view.effectiveVersion());
        assertEquals("1.21", view.defaultVersion());
        assertEquals(Optional.of("1.20.6"), service.effectiveVersion());
        assertEquals("1.20.6", settings.gameVersion());
    }

    @Test
    void pinnedVersionUnpublishedDegradesToDefaultWithFlag() {
        StubMcWikiApi api = published("1.21", List.of("1.20.6", "1.21"));
        InMemorySettings settings = new InMemorySettings();
        McguessSettingsService service = service(Optional.of(api), settings);
        service.save("1.20.6");
        // mc-wiki 侧取消发布 1.20.6：钉住失效，自动降级回默认
        api.publishedList = List.of("1.21");
        assertEquals(Optional.of("1.21"), service.effectiveVersion());
        McguessSettingsView view = service.view();
        assertEquals("1.20.6", view.gameVersion());
        assertTrue(view.pinnedMissing());
        assertEquals("1.21", view.effectiveVersion());
    }

    @Test
    void saveRejectsVersionNotPublished() {
        StubMcWikiApi api = published("1.21", List.of("1.21"));
        InMemorySettings settings = new InMemorySettings();
        McguessSettingsService service = service(Optional.of(api), settings);
        assertThrows(IllegalArgumentException.class, () -> service.save("9.9.9"));
        assertEquals("", settings.gameVersion());
    }

    @Test
    void blankSaveClearsPinAndFollowsDefault() {
        StubMcWikiApi api = published("1.21", List.of("1.20.6", "1.21"));
        InMemorySettings settings = new InMemorySettings();
        McguessSettingsService service = service(Optional.of(api), settings);
        service.save("1.20.6");
        McguessSettingsView view = service.save("  ");
        assertEquals("", settings.gameVersion());
        assertEquals("", view.gameVersion());
        assertEquals("1.21", view.effectiveVersion());
        assertEquals(Optional.of("1.21"), service.effectiveVersion());
    }

    @Test
    void providerUnavailableDegradesAndBlocksNonBlankSave() {
        InMemorySettings settings = new InMemorySettings();
        McguessSettingsService service = service(Optional.empty(), settings);
        assertEquals(Optional.empty(), service.effectiveVersion());
        McguessSettingsView view = service.view();
        assertFalse(view.providerAvailable());
        assertTrue(view.publishedVersions().isEmpty());
        assertEquals(null, view.effectiveVersion());
        // 清除钉住（空串）不依赖 provider，随时可用
        service.save("");
        assertThrows(IllegalStateException.class, () -> service.save("1.21"));
        assertEquals("", settings.gameVersion());
    }
}
