package online.yudream.base.plugin.mcguess.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McRecipe;
import org.junit.jupiter.api.Test;

/** 懒加载目录源：首次访问才构建、同版本复用、版本变化重建、provider 暂缺时服务旧目录。 */
class WikiCatalogSourceTest {

    private static StubMcWikiApi apiWith(String version, String itemId) {
        StubMcWikiApi api = new StubMcWikiApi();
        api.published = version;
        api.itemList = List.of(new McItemEntry(version, itemId, "item", "Oak Log", "橡木原木", List.of(), null, ""));
        api.recipeList = List.of(new McRecipe("planks", version, "minecraft:crafting_shapeless", itemId, 4, List.of(itemId), List.of(), ""));
        return api;
    }

    /** 生效版本跟随 stub 当前发布版本（默认场景，无钉住）。 */
    private static java.util.function.Supplier<Optional<String>> followsPublished(StubMcWikiApi api) {
        return () -> Optional.ofNullable(api.published);
    }

    @Test
    void buildsOnFirstAccessAndCachesPerVersion() {
        StubMcWikiApi api = apiWith("1.20.6", "minecraft:oak_log");
        WikiCatalogSource source = new WikiCatalogSource(() -> Optional.of(api), followsPublished(api));
        assertEquals(0, api.itemsCalls);
        McCatalog first = source.get();
        assertEquals(1, api.itemsCalls);
        assertEquals(1, api.recipesCalls);
        assertSame(first, source.get());
        assertEquals(1, api.itemsCalls);
    }

    @Test
    void rebuildsWhenPublishedVersionChanges() {
        StubMcWikiApi api = apiWith("1.20.6", "minecraft:oak_log");
        WikiCatalogSource source = new WikiCatalogSource(() -> Optional.of(api), followsPublished(api));
        McCatalog first = source.get();
        api.published = "1.21";
        api.itemList = List.of(new McItemEntry("1.21", "minecraft:spruce_log", "item", "Spruce Log", "云杉原木", List.of(), null, ""));
        McCatalog second = source.get();
        assertEquals(2, api.itemsCalls);
        assertTrue(second.byId("minecraft:spruce_log").isPresent());
        assertTrue(second.byId("minecraft:oak_log").isEmpty());
    }

    @Test
    void servesStaleCatalogWhileProviderUnavailable() {
        StubMcWikiApi api = apiWith("1.20.6", "minecraft:oak_log");
        AtomicInteger outages = new AtomicInteger();
        WikiCatalogSource source = new WikiCatalogSource(() -> outages.get() > 0 ? Optional.empty() : Optional.of(api),
                followsPublished(api));
        McCatalog first = source.get();
        outages.incrementAndGet();
        assertSame(first, source.get());
    }

    @Test
    void failsFastWhenNeverPublished() {
        WikiCatalogSource source = new WikiCatalogSource(() -> Optional.of(new StubMcWikiApi()), Optional::empty);
        assertThrows(IllegalStateException.class, source::get);
    }

    @Test
    void rebuildsWhenPinnedEffectiveVersionDiffersFromDefault() {
        StubMcWikiApi api = apiWith("1.20.6", "minecraft:oak_log");
        AtomicReference<String> pinned = new AtomicReference<>("1.20.6");
        WikiCatalogSource source = new WikiCatalogSource(() -> Optional.of(api), () -> Optional.ofNullable(pinned.get()));
        McCatalog first = source.get();
        assertEquals("1.20.6", first.version());
        // 钉住到另一个已发布版本：默认发布版本未动，目录仍应按生效版本重建
        pinned.set("1.21");
        api.itemList = List.of(new McItemEntry("1.21", "minecraft:spruce_log", "item", "Spruce Log", "云杉原木", List.of(), null, ""));
        api.recipeList = List.of();
        McCatalog second = source.get();
        assertEquals(2, api.itemsCalls);
        assertEquals("1.21", second.version());
        assertTrue(second.byId("minecraft:spruce_log").isPresent());
        assertTrue(second.byId("minecraft:oak_log").isEmpty());
    }

    @Test
    void rebuildsWhenRendersBecomeAvailableOnSameVersion() {
        StubMcWikiApi api = apiWith("1.20.6", "minecraft:oak_log");
        WikiCatalogSource source = new WikiCatalogSource(() -> Optional.of(api), followsPublished(api));
        McCatalog first = source.get();
        assertTrue(first.iconItems().isEmpty());
        api.renders.put("minecraft:oak_log", new byte[]{1});
        McCatalog second = source.get();
        assertEquals(2, api.itemsCalls);
        assertEquals(1, second.iconItemCount());
        assertSame(second, source.get());
        assertEquals(2, api.itemsCalls);
    }
}
