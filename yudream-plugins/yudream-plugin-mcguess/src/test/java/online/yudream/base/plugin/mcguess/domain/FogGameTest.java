package online.yudream.base.plugin.mcguess.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迷雾对局：阶段递增上限、重复猜测识别与恢复夹取。
 */
class FogGameTest {

    private FogGame newGame() {
        return new FogGame("g1", "conn", "qq", "chan", "diamond_sword", "10001", "u1", 1L);
    }

    @Test
    void stageUpCapsAtMaxStage() {
        FogGame game = newGame();
        assertEquals(0, game.getStage());
        for (int i = 0; i < FogGame.MAX_STAGE + 3; i++) {
            game.stageUp();
        }
        assertEquals(FogGame.MAX_STAGE, game.getStage(), "阶段不超过上限");
    }

    @Test
    void restoreStageClampsIntoRange() {
        FogGame game = newGame();
        game.restoreStage(99);
        assertEquals(FogGame.MAX_STAGE, game.getStage());
        game.restoreStage(-3);
        assertEquals(0, game.getStage());
        game.restoreStage(3);
        assertEquals(3, game.getStage());
    }

    @Test
    void hasGuessedTracksMatchedItems() {
        FogGame game = newGame();
        assertFalse(game.hasGuessed("diamond"));
        game.addGuess(new FogGame.FogGuess("钻石", "diamond", "钻石", FogGame.FogGuess.RESULT_MISS, "qq", "u", 2L));
        assertTrue(game.hasGuessed("diamond"));
        assertFalse(game.hasGuessed("stick"));
        assertFalse(game.hasGuessed(null));
    }

    @Test
    void winAndLoseSetTerminalState() {
        FogGame won = newGame();
        won.win("qqW", "uW", 5L);
        assertEquals(FogGame.STATUS_WON, won.getStatus());
        assertFalse(won.isPlaying());
        assertEquals("uW", won.getWinnerUserId());
        assertEquals(5L, won.getEndedAt());

        FogGame lost = newGame();
        lost.lose(6L);
        assertEquals(FogGame.STATUS_LOST, lost.getStatus());
        assertFalse(lost.isPlaying());
    }
}
