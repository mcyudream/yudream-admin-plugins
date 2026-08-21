package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 找茬出题器：展示网格与真实配方仅在错误格不同（基于真实数据集）。
 */
class SpotGeneratorTest {

    private static McCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.load(SpotGeneratorTest.class.getClassLoader());
    }

    @Test
    void gridDiffersFromRecipeInExactlyOneCell() {
        SpotGenerator generator = new SpotGenerator(catalog);
        for (long seed = 7; seed <= 12; seed++) {
            SpotGenerator.SpotPuzzle puzzle = generator.generate(new Random(seed));
            assertEquals(9, puzzle.grid().size());
            assertTrue(SpotGame.isValidCell(puzzle.wrongCell()));
            McRecipe recipe = catalog.recipeOf(puzzle.targetId()).orElseThrow();
            int diffs = 0;
            int diffIndex = -1;
            for (int i = 0; i < 9; i++) {
                String expected = recipe.grid().get(i);
                String actual = puzzle.grid().get(i);
                if (expected == null ? actual != null : !expected.equals(actual)) {
                    diffs++;
                    diffIndex = i;
                }
            }
            assertEquals(1, diffs, "展示网格与真实配方只能差一格");
            assertEquals(puzzle.wrongCell() - 1, diffIndex, "差异格就是错误格");
            assertEquals(recipe.grid().get(diffIndex), puzzle.correctId(), "correctId 是该格原本的物品");
            assertNotNull(puzzle.grid().get(diffIndex), "错误格不能为空位");
            assertNotEquals(puzzle.correctId(), puzzle.grid().get(diffIndex), "替换物与原物品不同");
        }
    }

    @Test
    void replacementNeverAppearsElsewhereInRecipe() {
        SpotGenerator generator = new SpotGenerator(catalog);
        SpotGenerator.SpotPuzzle puzzle = generator.generate(new Random(99L));
        McRecipe recipe = catalog.recipeOf(puzzle.targetId()).orElseThrow();
        String replacement = puzzle.grid().get(puzzle.wrongCell() - 1);
        assertTrue(!recipe.grid().contains(replacement), "替换物不能是配方中已有的原料");
        assertTrue(catalog.byId(replacement).map(McItem::icon).orElse(false), "替换物必须有图标");
    }
}
