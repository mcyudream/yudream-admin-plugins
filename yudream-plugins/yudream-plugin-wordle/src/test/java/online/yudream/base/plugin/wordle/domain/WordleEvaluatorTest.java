package online.yudream.base.plugin.wordle.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordleEvaluatorTest {

    @Test
    void evaluatesExactMatch() {
        List<LetterState> states = WordleEvaluator.evaluate("cigar", "cigar");
        assertEquals(List.of(LetterState.CORRECT, LetterState.CORRECT, LetterState.CORRECT,
                LetterState.CORRECT, LetterState.CORRECT), states);
        assertTrue(WordleEvaluator.isSolved(states));
    }

    @Test
    void duplicateLettersDoNotLeak() {
        // answer aabbc / guess abaab：两个 b 都应有额度，第三个 a 超出答案剩余额度
        List<LetterState> states = WordleEvaluator.evaluate("aabbc", "abaab");
        assertEquals(List.of(LetterState.CORRECT, LetterState.PRESENT, LetterState.PRESENT,
                LetterState.ABSENT, LetterState.PRESENT), states);
        assertFalse(WordleEvaluator.isSolved(states));
    }

    @Test
    void duplicateGuessLetterOnlyCountsOnceWhenAnswerHasOne() {
        // answer eager 只有一个剩余 e，guess eerie 中第三个 e 应判 ABSENT
        List<LetterState> states = WordleEvaluator.evaluate("eager", "eerie");
        assertEquals(List.of(LetterState.CORRECT, LetterState.PRESENT, LetterState.PRESENT,
                LetterState.ABSENT, LetterState.ABSENT), states);
    }

    @Test
    void evaluatesIdiomByHanzi() {
        List<LetterState> states = WordleEvaluator.evaluate("一心一意", "一心二用");
        assertEquals(List.of(LetterState.CORRECT, LetterState.CORRECT, LetterState.ABSENT, LetterState.ABSENT), states);
    }

    @Test
    void tilesRenderEmoji() {
        assertEquals("🟩🟨⬜", WordleEvaluator.tiles(List.of(LetterState.CORRECT, LetterState.PRESENT, LetterState.ABSENT)));
    }

    @Test
    void validatesGuessFormat() {
        assertTrue(WordleEvaluator.isValidGuess(WordleMode.ENGLISH, "apple", 5));
        assertFalse(WordleEvaluator.isValidGuess(WordleMode.ENGLISH, "Apple", 5));
        assertFalse(WordleEvaluator.isValidGuess(WordleMode.ENGLISH, "app1e", 5));
        assertFalse(WordleEvaluator.isValidGuess(WordleMode.ENGLISH, "app", 5));
        assertTrue(WordleEvaluator.isValidGuess(WordleMode.IDIOM, "画蛇添足", 4));
        assertFalse(WordleEvaluator.isValidGuess(WordleMode.IDIOM, "画蛇添", 4));
        assertFalse(WordleEvaluator.isValidGuess(WordleMode.IDIOM, "画蛇添a", 4));
    }

    @Test
    void hardModeRequiresCorrectPositions() {
        WordleGame game = new WordleGame("g1", "conn", "milky", "8888", WordleMode.ENGLISH, "crane", true,
                "10001", "1", System.currentTimeMillis());
        game.addGuess(new Guess("crabs", WordleEvaluator.evaluate("crane", "crabs"), "1", "10001", 0));
        assertNull(WordleEvaluator.checkHardMode(game, "cramp"));
        String violation = WordleEvaluator.checkHardMode(game, "pearl");
        assertNotNull(violation);
        assertTrue(violation.contains("第 1 位"));
    }

    @Test
    void hardModeRequiresPresentLetters() {
        WordleGame game = new WordleGame("g1", "conn", "milky", "8888", WordleMode.ENGLISH, "brick", true,
                "10001", "1", System.currentTimeMillis());
        // 猜测 weird：i 在第三位确定为 CORRECT，r 为 PRESENT —— 后续猜测第三位必须是 i 且必须含 r
        game.addGuess(new Guess("weird", WordleEvaluator.evaluate("brick", "weird"), "1", "10001", 0));
        assertNull(WordleEvaluator.checkHardMode(game, "grind"));
        String violation = WordleEvaluator.checkHardMode(game, "naive");
        assertNotNull(violation);
        assertTrue(violation.contains("必须包含"));
    }
}
