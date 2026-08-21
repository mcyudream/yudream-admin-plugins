package online.yudream.base.plugin.mcguess.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快答对局：逐题推进、答错禁答、计分板排序与最高分结算。
 */
class QuizGameTest {

    private QuizGame newGame() {
        List<QuizGame.Question> questions = List.of(
                new QuizGame.Question("t0", "i0", 2, List.of(1, 2, 3, 4)),
                new QuizGame.Question("t1", "i1", 3, List.of(2, 3, 4, 6)),
                new QuizGame.Question("t2", "i2", 1, List.of(1, 2, 3, 5)),
                new QuizGame.Question("t3", "i3", 4, List.of(2, 3, 4, 8)),
                new QuizGame.Question("t4", "i4", 5, List.of(3, 4, 5, 10)));
        return new QuizGame("g1", "conn", "qq", "chan", questions, "10001", "u1", 1L);
    }

    @Test
    void questionsAdvanceInOrder() {
        QuizGame game = newGame();
        assertEquals(5, game.questionCount());
        assertEquals(0, game.currentQuestionIndex());
        assertFalse(game.isComplete());
        game.solve(0, "qqA", "uA");
        assertTrue(game.isSolved(0));
        assertEquals(1, game.currentQuestionIndex(), "第一题答出后推进到第二题");
        for (int i = 1; i < 5; i++) {
            game.solve(i, "qqA", "uA");
        }
        assertEquals(-1, game.currentQuestionIndex());
        assertTrue(game.isComplete());
    }

    @Test
    void wrongAnswerBlocksOnlyThatQuestion() {
        QuizGame game = newGame();
        game.markWrong(0, "uB");
        assertTrue(game.hasAnsweredWrong(0, "uB"));
        assertFalse(game.hasAnsweredWrong(1, "uB"), "其他题不受答错影响");
        assertFalse(game.hasAnsweredWrong(0, "uC"), "其他人不受答错影响");
        assertFalse(game.hasAnsweredWrong(0, null));
    }

    @Test
    void scoreboardOrdersByScoreThenFirstSolve() {
        QuizGame game = newGame();
        // uA 答对第 0、2 题，uB 答对第 1、3 题，uC 答对第 4 题
        game.solve(0, "qqA", "uA");
        game.solve(1, "qqB", "uB");
        game.solve(2, "qqA", "uA");
        game.solve(3, "qqB", "uB");
        game.solve(4, "qqC", "uC");
        List<QuizGame.QuizScore> board = game.scoreboard();
        assertEquals(3, board.size());
        assertEquals("uA", board.get(0).userId(), "同分时先答对者排前");
        assertEquals(2, board.get(0).score());
        assertEquals("uB", board.get(1).userId());
        assertEquals("uC", board.get(2).userId());
        assertEquals(1, board.get(2).score());
        QuizGame.QuizScore top = game.topScorer();
        assertEquals("uA", top.userId());
        assertEquals("qqA", top.qq());
    }

    @Test
    void topScorerIsNullWithoutAnySolve() {
        QuizGame game = newGame();
        assertNull(game.topScorer());
        assertTrue(game.scoreboard().isEmpty());
    }

    @Test
    void restoreQuestionStateReplaysProgress() {
        QuizGame game = newGame();
        game.restoreQuestionState(2, "qqX", "uX", java.util.Set.of("uY", "uZ"));
        assertTrue(game.isSolved(2));
        assertEquals("uX", game.correctUserIdOf(2));
        assertEquals("qqX", game.correctQqOf(2));
        assertTrue(game.hasAnsweredWrong(2, "uY"));
        assertTrue(game.hasAnsweredWrong(2, "uZ"));
        assertEquals(0, game.currentQuestionIndex(), "中间题已答出时仍从第一题开始");
    }
}
