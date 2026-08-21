package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实数据集（mcguess/mcdata.json）验证目录加载、智能匹配与合成树计算。
 */
class McCatalogTest {

    private static McCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.load(McCatalogTest.class.getClassLoader());
    }

    @Test
    void datasetCoversAllItems() {
        assertTrue(catalog.items().size() > 1300, "应覆盖 1.20.5 全物品");
        assertTrue(catalog.craftableCount() > 700, "应包含全部合成配方");
        assertTrue(catalog.guessTargetCount() > 100, "随机出题候选池不能太小");
    }

    @Test
    void exactZhMatch() {
        List<McItem> matched = catalog.match("钻石剑");
        assertEquals(1, matched.size());
        assertEquals("diamond_sword", matched.getFirst().id());
    }

    @Test
    void smartMatchIgnoresColor() {
        // 「红色羊毛」是精确物品名，精确匹配优先
        List<McItem> exact = catalog.match("红色羊毛");
        assertEquals(1, exact.size());
        assertEquals("red_wool", exact.getFirst().id());
        // 不带颜色的「羊毛」归一化后匹配全部颜色变体
        List<McItem> wools = catalog.match("羊毛");
        assertTrue(wools.size() >= 16, "羊毛应匹配全部颜色变体");
        assertTrue(wools.stream().anyMatch(item -> item.id().equals("orange_wool")));
        assertTrue(wools.stream().allMatch(item -> item.id().endsWith("_wool")));
    }

    @Test
    void smartMatchIgnoresColorAndMaterial() {
        // 「红色玻璃板」应匹配「紫色染色玻璃板」等染色玻璃板
        List<McItem> panes = catalog.match("红色玻璃板");
        assertTrue(panes.stream().anyMatch(item -> item.id().equals("purple_stained_glass_pane")));
    }

    @Test
    void smartMatchIgnoresOverworldWood() {
        // 「橡木木板」归一化后与「云杉木板」同族
        assertEquals(McCatalog.normalizeZh("云杉木板"), McCatalog.normalizeZh("橡木木板"));
        // 下界木材不参与忽略
        assertEquals("绯红木板", McCatalog.normalizeZh("绯红木板"));
        assertFalse(McCatalog.normalizeZh("绯红木板").equals(McCatalog.normalizeZh("橡木木板")));
    }

    @Test
    void randomTargetPicksCraftableNonTrivialTarget() {
        Random random = new Random(20260821L);
        for (int i = 0; i < 20; i++) {
            McItem target = catalog.randomTarget(random);
            assertTrue(target.craftable(), "出题目标必须可合成");
            assertTrue(catalog.treeOf(target.id()).nodeCount() >= 3, "出题目标的合成树必须非平凡");
        }
    }

    @Test
    void craftingTreeDistanceAndOccurrences() {
        McCatalog.TreeInfo tree = catalog.treeOf("diamond_sword");
        assertEquals(1, tree.distanceOf("diamond"));
        assertEquals(1, tree.distanceOf("stick"));
        // 出现次数统计整棵合成树：剑 2 颗 + 钻石块配方 9 颗 = 11
        assertTrue(tree.occurrencesOf("diamond") >= 2);
        assertEquals(1, tree.occurrencesOf("stick"));
        assertNull(tree.distanceOf("dirt"), "无关物品不可达");
    }

    @Test
    void recipeGridIsAnchored() {
        McRecipe sword = catalog.recipeOf("diamond_sword").orElseThrow();
        assertEquals(9, sword.grid().size());
        assertEquals("diamond", sword.grid().get(0));
        assertEquals("stick", sword.grid().get(6));
        assertNull(sword.grid().get(1));
    }
}
