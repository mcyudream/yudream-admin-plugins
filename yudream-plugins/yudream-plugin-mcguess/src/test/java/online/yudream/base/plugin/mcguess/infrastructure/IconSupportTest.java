package online.yudream.base.plugin.mcguess.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 图标支持：按物品点查共享渲染资产并缓存 data URI；发布版本变化时缓存失效，渲染资产更新后未命中项自愈。 */
class IconSupportTest {

    private static StubMcWikiApi apiWithRender(String version, String itemId, byte[] png) {
        StubMcWikiApi api = new StubMcWikiApi();
        api.published = version;
        if (png != null) api.renders.put(itemId, png);
        return api;
    }

    /** 生效版本跟随 stub 当前发布版本（默认场景，无钉住）。 */
    private static IconSupport iconsFollowingPublished(StubMcWikiApi api) {
        return new IconSupport(() -> Optional.of(api), () -> Optional.ofNullable(api.published));
    }

    @Test
    void resolvesAndCachesDataUriPerItem() {
        StubMcWikiApi api = apiWithRender("1.20.6", "minecraft:oak_log", new byte[] { 1, 2, 3 });
        IconSupport icons = iconsFollowingPublished(api);
        String uri = icons.dataUri("minecraft:oak_log");
        assertNotNull(uri);
        assertTrue(uri.startsWith("data:image/png;base64,"));
        assertEquals(uri, icons.dataUri("minecraft:oak_log"));
    }

    @Test
    void unknownItemAndMissingRenderDegradeToNull() {
        StubMcWikiApi api = apiWithRender("1.20.6", "minecraft:oak_log", null);
        IconSupport icons = iconsFollowingPublished(api);
        assertNull(icons.dataUri("minecraft:oak_log"));
        assertNull(icons.dataUri("minecraft:nonexistent"));
        assertNull(icons.dataUri(null));
    }

    @Test
    void versionFlipClearsCaches() {
        StubMcWikiApi api = apiWithRender("1.20.6", "minecraft:oak_log", new byte[] { 1 });
        IconSupport icons = iconsFollowingPublished(api);
        String before = icons.dataUri("minecraft:oak_log");
        // 换发布版本后渲染图更新（如同名渲染资产换新），应重新点查而不是沿用旧缓存
        api.published = "1.21";
        api.renders.put("minecraft:oak_log", new byte[] { 9, 9 });
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
    void pinnedVersionStillReadsSharedRenders() {
        // 渲染资产全版本共用：钉住旧版本时同样取共享渲染图
        StubMcWikiApi api = apiWithRender("1.21", "minecraft:oak_log", new byte[] { 9 });
        IconSupport icons = new IconSupport(() -> Optional.of(api), () -> Optional.of("1.20.6"));
        String uri = icons.dataUri("minecraft:oak_log");
        assertNotNull(uri);
        assertTrue(uri.endsWith(Base64.getEncoder().encodeToString(new byte[] { 9 })));
    }

    @Test
    void missingRenderIsNotCachedSoAssetUpdatePicksUp() {
        StubMcWikiApi api = apiWithRender("1.20.6", "minecraft:oak_log", null);
        IconSupport icons = iconsFollowingPublished(api);
        assertNull(icons.dataUri("minecraft:oak_log"));
        // 管理端一键更新渲染资产后，同一版本内未命中项应自愈出图（无负缓存）
        api.renders.put("minecraft:oak_log", new byte[] { 7, 7 });
        assertNotNull(icons.dataUri("minecraft:oak_log"));
    }

    @Test
    void compactPngShrinksLargeIconsForTemplateEmbedding() throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        byte[] source = out.toByteArray();
        byte[] compacted = IconSupport.compactPng(source);
        assertTrue(compacted.length > 0);
        assertTrue(compacted.length < source.length);
    }
}
