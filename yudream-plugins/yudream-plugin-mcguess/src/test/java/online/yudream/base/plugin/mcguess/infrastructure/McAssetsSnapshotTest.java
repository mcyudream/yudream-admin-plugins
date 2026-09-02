package online.yudream.base.plugin.mcguess.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McRecipe;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import org.junit.jupiter.api.Test;

class McAssetsSnapshotTest {

    private static McItemEntry item(String id) {
        return new McItemEntry("1.20.6", id, "block", id, id, List.of(), null, "");
    }

    private static McWikiApi.McRecipe recipe(String id, String type, String resultId, int count, List<String> ingredients, List<String> grid) {
        return new McWikiApi.McRecipe(id, "1.20.6", type, resultId, count, ingredients, grid, "{}");
    }

    @Test
    void catalogKeysRecipesByResultAndPrefersCraftingTableVariants() {
        List<String> shapedGrid = Arrays.asList("minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
                "minecraft:oak_planks", null, "minecraft:oak_planks",
                "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks");
        List<McWikiApi.McRecipe> recipes = List.of(
                // 先出现切石配方（低优先级），后出现合成台有序配方（高优先级）
                recipe("chest_from_stonecutting", "minecraft:stonecutting", "minecraft:chest", 1,
                        List.of("minecraft:stone"), Arrays.asList("minecraft:stone", null, null, null, null, null, null, null, null)),
                recipe("chest", "minecraft:crafting_shaped", "minecraft:chest", 1,
                        List.of("#minecraft:planks"), shapedGrid));
        McCatalog catalog = new McAssetsSnapshot("1.20.6", List.of(item("minecraft:chest")), recipes).catalog();
        McRecipe chest = catalog.recipeOf("minecraft:chest").orElseThrow();
        assertEquals(9, chest.grid().size());
        assertEquals("minecraft:oak_planks", chest.grid().get(0));
        assertNull(chest.grid().get(4));
        assertEquals("minecraft:oak_planks", chest.grid().get(8));
    }

    @Test
    void legacyDataWithoutGridFallsBackToFlatIngredientsSkippingTags() {
        McWikiApi.McRecipe legacy = recipe("iron_ingot_from_block", "minecraft:crafting_shapeless", "minecraft:iron_ingot", 9,
                List.of("#minecraft:storage_blocks", "minecraft:iron_block"), List.of());
        McCatalog catalog = new McAssetsSnapshot("1.20.6", List.of(item("minecraft:iron_ingot")), List.of(legacy)).catalog();
        McRecipe recipe = catalog.recipeOf("minecraft:iron_ingot").orElseThrow();
        assertEquals(9, recipe.grid().size());
        assertEquals("minecraft:iron_block", recipe.grid().get(0));
        assertNull(recipe.grid().get(1));
    }

    @Test
    void recipeTreeMakesCraftableItemsGuessableAgain() {
        // 原木 -> 木板 -> 箱子 的三级链，修复前因配方键错导致 guessTargets 为空、randomTarget 抛 bound must be positive
        List<McWikiApi.McRecipe> recipes = List.of(
                recipe("oak_planks", "minecraft:crafting_shapeless", "minecraft:oak_planks", 4,
                        List.of("#minecraft:oak_logs"), Arrays.asList("minecraft:oak_log", null, null, null, null, null, null, null, null)),
                recipe("chest", "minecraft:crafting_shaped", "minecraft:chest", 1,
                        List.of("#minecraft:planks"), Arrays.asList("minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
                                "minecraft:oak_planks", null, "minecraft:oak_planks",
                                "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks")));
        McCatalog catalog = new McAssetsSnapshot("1.20.6",
                List.of(item("minecraft:oak_log"), item("minecraft:oak_planks"), item("minecraft:chest")), recipes).catalog();
        assertTrue(catalog.guessTargetCount() > 0);
        assertTrue(catalog.treeOf("minecraft:chest").nodeCount() >= 3);
        assertDoesNotThrow(() -> catalog.randomTarget(new Random(42)));
    }
}
