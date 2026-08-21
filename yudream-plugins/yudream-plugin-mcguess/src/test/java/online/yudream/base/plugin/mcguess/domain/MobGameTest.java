package online.yudream.base.plugin.mcguess.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 猜生物对局：填格判定、扣心、胜负与仓储恢复。
 */
class MobGameTest {

    private static final McMobCatalog.McMob ZOMBIE =
            new McMobCatalog.McMob("zombie", "僵尸", List.of("hostile", "undead", "overworld"));
    private static final McMobCatalog.McMob CREEPER =
            new McMobCatalog.McMob("creeper", "苦力怕", List.of("hostile", "overworld", "terraform"));

    private static MobGame newGame() {
        return new MobGame("g1", "conn-1", "qq", "20001",
                List.of("hostile", "undead", "overworld"),
                List.of("hostile", "undead", "overworld"),
                List.of("zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie"),
                "10001", "1", 1000L);
    }

    @Test
    void satisfiesRequiresRowAndColumn() {
        MobGame game = newGame();
        // 1 号格 = 行 hostile + 列 hostile：僵尸满足
        assertTrue(game.satisfies(1, ZOMBIE));
        // 5 号格 = 行 undead + 列 undead：苦力怕不满足
        assertFalse(game.satisfies(5, CREEPER));
        // 9 号格 = 行 overworld + 列 overworld：两者都满足
        assertTrue(game.satisfies(9, CREEPER));
    }

    @Test
    void fillTracksCellsAndUsage() {
        MobGame game = newGame();
        assertTrue(MobGame.isValidCell(1));
        assertTrue(MobGame.isValidCell(9));
        assertFalse(MobGame.isValidCell(0));
        assertFalse(MobGame.isValidCell(10));
        assertFalse(game.isFilled(1));
        game.fill(1, "zombie");
        assertTrue(game.isFilled(1));
        assertTrue(game.hasUsed("zombie"));
        assertFalse(game.hasUsed("creeper"));
        assertEquals(1, game.filledCount());
        assertFalse(game.isComplete());
    }

    @Test
    void heartsDrainToZeroAndNoFurther() {
        MobGame game = newGame();
        assertEquals(MobGame.MAX_HEARTS, game.getHearts());
        for (int i = 0; i < 10; i++) {
            game.loseHeart();
        }
        assertEquals(0, game.getHearts(), "心数不能扣成负数");
    }

    @Test
    void winAndLoseSetTerminalState() {
        MobGame won = newGame();
        won.win("10001", "1", 2000L);
        assertEquals(MobGame.STATUS_WON, won.getStatus());
        assertFalse(won.isPlaying());
        assertEquals("10001", won.getWinnerQq());
        assertEquals(2000L, won.getEndedAt());

        MobGame lost = newGame();
        lost.lose(3000L);
        assertEquals(MobGame.STATUS_LOST, lost.getStatus());
        assertNull(lost.getWinnerQq());
    }

    @Test
    void fullBoardIsComplete() {
        MobGame game = newGame();
        for (int cell = 1; cell <= 9; cell++) {
            game.fill(cell, "mob-" + cell);
        }
        assertEquals(9, game.filledCount());
        assertTrue(game.isComplete());
    }

    @Test
    void restoreCellsPadsAndKeepsNulls() {
        MobGame game = newGame();
        game.restoreCells(Arrays.asList("zombie", null, "creeper"));
        assertEquals("zombie", game.getCells().get(0));
        assertNull(game.getCells().get(1));
        assertEquals("creeper", game.getCells().get(2));
        assertEquals(2, game.filledCount());
        game.restoreHearts(3);
        assertEquals(3, game.getHearts());
    }
}
