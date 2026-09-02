package online.yudream.base.plugin.mcguess.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McRecipe;
import org.junit.jupiter.api.Test;

/** McCatalog 懒加载模式：构造不触碰 source，全部公开方法委托给解析出的实际目录且行为不变。 */
class McCatalogLazyTest {

    /** 橡木原木 → 橡木木板 → 箱子 的最小合成链（箱子为可出题目标）。 */
    private static McCatalog chain() {
        List<McItem> items = List.of(
                new McItem("minecraft:oak_log", "Oak Log", "橡木原木", false, false),
                new McItem("minecraft:oak_planks", "Oak Planks", "橡木木板", true, false),
                new McItem("minecraft:chest", "Chest", "箱子", true, false));
        Map<String, McRecipe> recipes = Map.of(
                "minecraft:oak_planks", new McRecipe("minecraft:oak_planks", Arrays.asList("minecraft:oak_log", null, null, null, null, null, null, null, null), 4),
                "minecraft:chest", new McRecipe("minecraft:chest", Arrays.asList("minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", null, "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks"), 1));
        return new McCatalog(items, recipes, "test");
    }

    @Test
    void supplierIsNotTouchedUntilFirstUse() {
        AtomicInteger calls = new AtomicInteger();
        McCatalog lazy = McCatalog.lazy(() -> { calls.incrementAndGet(); return chain(); });
        assertEquals(0, calls.get());
        lazy.craftableCount();
        assertTrue(calls.get() >= 1);
    }

    @Test
    void delegatesAllAccessorsToResolvedCatalog() {
        McCatalog lazy = McCatalog.lazy(McCatalogLazyTest::chain);
        assertEquals("test", lazy.version());
        assertEquals(3, lazy.items().size());
        assertEquals(2, lazy.recipes().size());
        assertEquals(2, lazy.craftableCount());
        assertEquals("橡木原木", lazy.byId("minecraft:oak_log").orElseThrow().zh());
        assertEquals(4, lazy.recipeOf("minecraft:oak_planks").orElseThrow().count());
        assertEquals(Optional.empty(), lazy.recipeOf("minecraft:oak_log"));
        assertEquals(List.of("minecraft:oak_log"), lazy.match("橡木原木").stream().map(McItem::id).toList());
        assertEquals(1, lazy.guessTargetCount());
        assertEquals("minecraft:chest", lazy.randomTarget(new Random(1)).id());
        assertTrue(lazy.iconItems().isEmpty());
        assertTrue(lazy.familyOf("minecraft:chest").isEmpty());
        // 合成树与出现分数走实际目录的缓存实现
        assertEquals(3, lazy.treeOf("minecraft:chest").nodeCount());
        assertEquals(8, lazy.occurrenceScore("minecraft:oak_planks"));
        assertEquals(McCatalog.normalizeZh("红色羊毛"), McCatalog.normalizeZh("红色羊毛"));
    }

    @Test
    void picksUpReplacementFromSource() {
        McCatalog first = chain();
        McCatalog second = new McCatalog(List.of(new McItem("minecraft:stick", "Stick", "木棍", false, false)), Map.of(), "test");
        McCatalog[] current = { first };
        McCatalog lazy = McCatalog.lazy(() -> current[0]);
        assertEquals(3, lazy.items().size());
        current[0] = second;
        assertEquals(1, lazy.items().size());
        assertEquals("木棍", lazy.byId("minecraft:stick").orElseThrow().zh());
    }

    @Test
    void lazyAndEagerBehaveIdentically() {
        McCatalog eager = chain();
        McCatalog lazy = McCatalog.lazy(McCatalogLazyTest::chain);
        assertEquals(eager.items(), lazy.items());
        assertEquals(eager.recipes(), lazy.recipes());
        assertEquals(eager.match("箱子"), lazy.match("箱子"));
        assertEquals(eager.treeOf("minecraft:chest").nodeCount(), lazy.treeOf("minecraft:chest").nodeCount());
        assertEquals(eager.occurrenceScore("minecraft:oak_planks"), lazy.occurrenceScore("minecraft:oak_planks"));
    }

    @Test
    void nullGridCellsArePreservedThroughDelegation() {
        McCatalog lazy = McCatalog.lazy(McCatalogLazyTest::chain);
        McRecipe chest = lazy.recipeOf("minecraft:chest").orElseThrow();
        assertNull(chest.grid().get(4));
        assertNotEquals(chest.grid().get(0), chest.grid().get(4));
    }
}
