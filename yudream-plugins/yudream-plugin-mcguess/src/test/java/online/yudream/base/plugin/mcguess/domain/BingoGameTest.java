package online.yudream.base.plugin.mcguess.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宾果对局：格子定位、认领与 12 条连线（5 行 + 5 列 + 2 对角线）的完成判定。
 */
class BingoGameTest {

    private BingoGame newGame() {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < BingoGame.CELL_COUNT; i++) {
            cells.add("item_" + i);
        }
        return new BingoGame("g1", "conn", "qq", "chan", cells, "10001", "u1", 1L);
    }

    @Test
    void linesCoverRowsColumnsDiagonals() {
        assertEquals(12, BingoGame.LINES.size(), "5 行 + 5 列 + 2 对角线");
        assertEquals(List.of(0, 1, 2, 3, 4), BingoGame.LINES.get(0), "第一行");
        assertEquals(List.of(0, 5, 10, 15, 20), BingoGame.LINES.get(5), "第一列");
        assertEquals(List.of(0, 6, 12, 18, 24), BingoGame.LINES.get(10), "主对角线");
        assertEquals(List.of(4, 8, 12, 16, 20), BingoGame.LINES.get(11), "副对角线");
    }

    @Test
    void cellIndexOfIsOneBased() {
        BingoGame game = newGame();
        assertEquals(1, game.cellIndexOf("item_0"));
        assertEquals(25, game.cellIndexOf("item_24"));
        assertEquals(-1, game.cellIndexOf("not_on_board"));
        assertTrue(BingoGame.isValidCell(1));
        assertTrue(BingoGame.isValidCell(25));
        assertTrue(!BingoGame.isValidCell(0));
        assertTrue(!BingoGame.isValidCell(26));
    }

    @Test
    void rowCompletionWins() {
        BingoGame game = newGame();
        assertNull(game.findCompletedLine());
        for (int cell = 1; cell <= 4; cell++) {
            game.claim(cell, "qq" + cell, "u" + cell);
        }
        assertNull(game.findCompletedLine(), "差一格不算完成");
        assertEquals(4, game.claimedCount());
        game.claim(5, "qq5", "u5");
        List<Integer> line = game.findCompletedLine();
        assertNotNull(line);
        assertEquals(List.of(0, 1, 2, 3, 4), line);
        assertTrue(game.isClaimed(5));
        assertEquals("u5", game.claimerOf(5));
        assertEquals("qq5", game.claimerQqOf(5));
    }

    @Test
    void columnAndDiagonalCompletion() {
        BingoGame byColumn = newGame();
        for (int cell = 1; cell <= 21; cell += 5) {
            byColumn.claim(cell, "qq", "u");
        }
        assertEquals(List.of(0, 5, 10, 15, 20), byColumn.findCompletedLine(), "第一列完成");

        BingoGame byDiagonal = newGame();
        for (int cell = 1; cell <= 25; cell += 6) {
            byDiagonal.claim(cell, "qq", "u");
        }
        assertEquals(List.of(0, 6, 12, 18, 24), byDiagonal.findCompletedLine(), "主对角线完成");

        BingoGame byAntiDiagonal = newGame();
        for (int cell = 5; cell <= 21; cell += 4) {
            byAntiDiagonal.claim(cell, "qq", "u");
        }
        assertEquals(List.of(4, 8, 12, 16, 20), byAntiDiagonal.findCompletedLine(), "副对角线完成");
    }

    @Test
    void scatteredClaimsDoNotComplete() {
        BingoGame game = newGame();
        game.claim(1, "qq", "u");
        game.claim(7, "qq", "u");
        game.claim(13, "qq", "u");
        game.claim(25, "qq", "u");
        assertNull(game.findCompletedLine());
        assertEquals(4, game.claimedCount());
    }
}
