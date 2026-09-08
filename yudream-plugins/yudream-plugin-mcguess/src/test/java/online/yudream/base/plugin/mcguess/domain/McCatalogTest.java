package online.yudream.base.plugin.mcguess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 出题池与合成树：增殖配方（产物自身也是原料，如锻造模板）不进出题池，树构建不循环。 */
class McCatalogTest {

    private static McItem item(String id, String zh, boolean craftable) {
        return new McItem(id, id, zh, craftable, true);
    }

    @Test
    void selfReferentialDuplicationRecipeIsExcludedFromGuessTargets() {
        // 下界合金升级锻造模板的增殖配方：1 模板 + 7 钻石 + 1 下界岩 → 2 模板，产物自身占一格。
        // 树有 3 个节点，若没有自引用排除会进池——猜物中该格永远无法作为普通格揭示且提示会泄题。
        McItem template = item("minecraft:netherite_upgrade_smithing_template", "下界合金升级", true);
        McItem diamond = item("minecraft:diamond", "钻石", false);
        McItem netherrack = item("minecraft:netherrack", "下界岩", false);
        McRecipe duplication = new McRecipe(template.id(), List.of(
                diamond.id(), template.id(), diamond.id(),
                diamond.id(), netherrack.id(), diamond.id(),
                diamond.id(), diamond.id(), diamond.id()), 2);
        McCatalog catalog = new McCatalog(List.of(template, diamond, netherrack), Map.of(template.id(), duplication), "test");
        assertEquals(0, catalog.guessTargetCount());
        // 合成树仍可正常构建（visited 语义防循环）：自身距离 0，原料计数如实
        McCatalog.TreeInfo tree = catalog.treeOf(template.id());
        assertEquals(0, tree.distanceOf(template.id()));
        assertEquals(1, tree.occurrencesOf(template.id()));
        assertEquals(7, tree.occurrencesOf(diamond.id()));
        assertEquals(1, tree.occurrencesOf(netherrack.id()));
    }

    @Test
    void normalCraftableChainStaysInGuessTargets() {
        McItem log = item("minecraft:oak_log", "橡木原木", true);
        McItem planks = item("minecraft:oak_planks", "橡木木板", true);
        McItem chest = item("minecraft:chest", "箱子", true);
        Map<String, McRecipe> recipes = Map.of(
                planks.id(), new McRecipe(planks.id(), java.util.Arrays.asList(
                        log.id(), null, null,
                        null, null, null,
                        null, null, null), 4),
                chest.id(), new McRecipe(chest.id(), java.util.Arrays.asList(
                        planks.id(), planks.id(), planks.id(),
                        planks.id(), null, planks.id(),
                        planks.id(), planks.id(), planks.id()), 1));
        McCatalog catalog = new McCatalog(List.of(log, planks, chest), recipes, "test");
        // 箱子树 3 节点（箱子/木板/原木）进池；木板树 2 节点、原木无配方，均不进池
        assertEquals(1, catalog.guessTargetCount());
        assertEquals(3, catalog.iconItemCount());
        assertSame(catalog.iconItems(), catalog.visualPool(1));
    }

    @Test
    void visualPoolFallsBackToAllItemsWhenNoIcons() {
        McItem log = new McItem("minecraft:oak_log", "oak_log", "橡木原木", false, false);
        McItem planks = new McItem("minecraft:oak_planks", "oak_planks", "橡木木板", true, false);
        McCatalog catalog = new McCatalog(List.of(log, planks), Map.of(), "test");
        assertEquals(0, catalog.iconItemCount());
        assertEquals(catalog.items(), catalog.visualPool(1));
        assertEquals(catalog.items(), catalog.visualPool(2));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> catalog.visualPool(25));
        assertTrue(error.getMessage().contains("可用物品不足 25"));
        assertTrue(error.getMessage().contains("带图标 0"));
    }

    @Test
    void randomTargetEmptyPoolThrowsChinese() {
        McCatalog catalog = new McCatalog(List.of(), Map.of(), "test");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> catalog.randomTarget(new Random(1)));
        assertTrue(error.getMessage().contains("没有可出题物品"));
    }

    @Test
    void visualPoolPrefersIconItemsWhenEnough() {
        List<McItem> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add(new McItem("minecraft:item_" + i, "item_" + i, "物品" + i, false, i < 10));
        }
        McCatalog catalog = new McCatalog(items, Map.of(), "test");
        assertEquals(10, catalog.iconItemCount());
        assertEquals(catalog.iconItems(), catalog.visualPool(10));
        assertEquals(catalog.items(), catalog.visualPool(11));
    }
}
