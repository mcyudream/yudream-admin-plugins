package online.yudream.base.plugin.mcguess.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import org.junit.jupiter.api.Test;

/** 贴图支持：按物品点查 textureKey 并缓存 data URI；发布版本变化时缓存失效。 */
class IconSupportTest {

    private static StubMcWikiApi apiWithIcon(String version, String itemId, String textureKey, byte[] png) {
        StubMcWikiApi api = new StubMcWikiApi();
        api.published = version;
        api.byId.put(itemId, new McItemEntry(version, itemId, "item", "Oak Log", "橡木原木", List.of(), textureKey, ""));
        if (textureKey != null) api.textures.put(textureKey, png);
        return api;
    }

    /** 生效版本跟随 stub 当前发布版本（默认场景，无钉住）。 */
    private static IconSupport iconsFollowingPublished(StubMcWikiApi api) {
        return new IconSupport(() -> Optional.of(api), () -> Optional.ofNullable(api.published));
    }

    @Test
    void resolvesAndCachesDataUriPerItem() {
        StubMcWikiApi api = apiWithIcon("1.20.6", "minecraft:oak_log", "block/oak_log.png", new byte[] { 1, 2, 3 });
        IconSupport icons = iconsFollowingPublished(api);
        String uri = icons.dataUri("minecraft:oak_log");
        assertNotNull(uri);
        assertTrue(uri.startsWith("data:image/png;base64,"));
        assertEquals(uri, icons.dataUri("minecraft:oak_log"));
    }

    @Test
    void unknownItemAndMissingTextureDegradeToNull() {
        StubMcWikiApi api = apiWithIcon("1.20.6", "minecraft:oak_log", null, null);
        IconSupport icons = iconsFollowingPublished(api);
        assertNull(icons.dataUri("minecraft:oak_log"));
        assertNull(icons.dataUri("minecraft:nonexistent"));
        assertNull(icons.dataUri(null));
    }

    @Test
    void versionFlipClearsCaches() {
        StubMcWikiApi api = apiWithIcon("1.20.6", "minecraft:oak_log", "block/oak_log.png", new byte[] { 1 });
        IconSupport icons = iconsFollowingPublished(api);
        String before = icons.dataUri("minecraft:oak_log");
        // 换发布版本后同物品 textureKey 变化，应重新点查而不是沿用旧缓存
        api.published = "1.21";
        api.byId.put("minecraft:oak_log", new McItemEntry("1.21", "minecraft:oak_log", "item", "Oak Log", "橡木原木", List.of(), "block/oak_log_v2.png", ""));
        api.textures.put("block/oak_log_v2.png", new byte[] { 9, 9 });
        String after = icons.dataUri("minecraft:oak_log");
        assertNotNull(after);
        assertTrue(!before.equals(after));
    }

    @Test
    void providerUnavailableDegradesToNull() {
        IconSupport icons = new IconSupport(Optional::empty, Optional::empty);
        assertNull(icons.dataUri("minecraft:oak_log"));
        StubMcWikiApi noVersion = new StubMcWikiApi();
        assertNull(new IconSupport(() -> Optional.of(noVersion), Optional::empty).dataUri("minecraft:oak_log"));
    }

    @Test
    void pinnedVersionSelectsThatVersionTextures() {
        StubMcWikiApi api = apiWithIcon("1.21", "minecraft:oak_log", "block/oak_log_v2.png", new byte[] { 9 });
        api.byId.put("1.20.6|minecraft:oak_log", new McItemEntry("1.20.6", "minecraft:oak_log", "item", "Oak Log", "橡木原木", List.of(), "block/oak_log.png", ""));
        api.textures.put("block/oak_log.png", new byte[] { 1 });
        // 钉住 1.20.6 时即使默认发布版本是 1.21，也必须取 1.20.6 的贴图
        IconSupport icons = new IconSupport(() -> Optional.of(api), () -> Optional.of("1.20.6"));
        String uri = icons.dataUri("minecraft:oak_log");
        assertNotNull(uri);
        assertTrue(uri.endsWith(Base64.getEncoder().encodeToString(new byte[] { 1 })));
    }
}
