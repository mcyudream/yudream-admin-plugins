package online.yudream.base.plugin.mcguess.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 猜合成对局：逐格揭示（同物品多格一并揭示）、空猜连击与提示、胜利判定。
 */
class RecipeGameTest {

    /** 铁镐配方：铁锭 铁锭 铁锭 / 空 木棍 空 / 空 木棍 空（含空位，用 Arrays.asList 允许 null）。 */
    private static RecipeGame pickaxe() {
        return new RecipeGame("r1", "conn-1", "qq", "20001", "iron_pickaxe",
                Arrays.asList("iron_ingot", "iron_ingot", "iron_ingot",
                        null, "stick", null,
                        null, "stick", null),
                "10001", "1", 1000L);
    }

    @Test
    void slotClassification() {
        RecipeGame game = pickaxe();
        assertTrue(RecipeGame.isValidCell(9));
        assertFalse(RecipeGame.isValidCell(10));
        assertFalse(game.isEmptySlot(1));
        assertTrue(game.isEmptySlot(4));
        assertTrue(game.matches(1, "iron_ingot"));
        assertFalse(game.matches(1, "stick"));
        assertFalse(game.matches(4, "stick"), "空位不匹配任何物品");
        assertFalse(game.isRevealedSlot(1));
        assertEquals(5, game.ingredientSlotCount());
    }

    @Test
    void revealItemRevealsAllSlotsOfSameIngredient() {
        RecipeGame game = pickaxe();
        assertEquals(List.of(1, 2, 3), game.revealItem("iron_ingot"));
        assertTrue(game.isRevealedSlot(1));
        assertTrue(game.isRevealedSlot(3));
        assertEquals(3, game.revealedSlotCount());
        assertFalse(game.isComplete());
        assertEquals(List.of("stick"), game.unrevealedIngredients());

        assertEquals(List.of(5, 8), game.revealItem("stick"));
        assertTrue(game.isComplete(), "全部原料揭示即获胜");
    }

    @Test
    void emptyStreakUnlocksHint() {
        RecipeGame game = pickaxe();
        assertFalse(game.hintAvailable());
        for (int i = 0; i < RecipeGame.HINT_EMPTY_STREAK; i++) {
            game.increaseEmptyStreak();
        }
        assertTrue(game.hintAvailable());
        game.useHint();
        assertEquals(1, game.getHintsUsed());
        assertEquals(0, game.getEmptyStreak(), "使用提示后连击清零");
        assertFalse(game.hintAvailable());
        game.increaseEmptyStreak();
        game.resetEmptyStreak();
        assertEquals(0, game.getEmptyStreak());
    }

    @Test
    void restoreCountersRoundTrip() {
        RecipeGame game = pickaxe();
        game.restoreCounters(4, 2);
        assertEquals(4, game.getEmptyStreak());
        assertEquals(2, game.getHintsUsed());
    }

    @Test
    void winAndLoseSetTerminalState() {
        RecipeGame game = pickaxe();
        game.win("10001", "1", 2000L);
        assertEquals(RecipeGame.STATUS_WON, game.getStatus());
        assertFalse(game.isPlaying());

        RecipeGame lost = pickaxe();
        lost.lose(3000L);
        assertEquals(RecipeGame.STATUS_LOST, lost.getStatus());
        assertEquals(3000L, lost.getEndedAt());
    }
}
