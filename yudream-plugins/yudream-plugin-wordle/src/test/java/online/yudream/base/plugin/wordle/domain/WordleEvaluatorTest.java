package online.yudream.base.plugin.wordle.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void idiomHintsMarkInitialFinalAndToneSeparately() {
        // 答案 一心一意 yī xīn yī yì，猜测 三心二意 sān xīn èr yì
        List<IdiomHint> hints = WordleEvaluator.evaluateIdiom("一心一意", "三心二意", stubPinyin());
        assertEquals(4, hints.size());
        // 三：声母 s 与韵母 an 均不在答案中，声调 1 与首字一致
        assertEquals(LetterState.ABSENT, hints.get(0).charState());
        assertEquals(LetterState.ABSENT, hints.get(0).initialState());
        assertEquals(LetterState.ABSENT, hints.get(0).finalState());
        assertEquals(LetterState.CORRECT, hints.get(0).toneState());
        // 心：完全命中
        assertEquals(LetterState.CORRECT, hints.get(1).initialState());
        assertEquals(LetterState.CORRECT, hints.get(1).finalState());
        assertEquals(LetterState.CORRECT, hints.get(1).toneState());
        // 二：零声母不产出声母块；答案唯一的 4 声额度已被「意」的同位命中消耗
        assertFalse(hints.get(2).pinyin().hasInitial());
        assertEquals(LetterState.ABSENT, hints.get(2).finalState());
        assertEquals(LetterState.ABSENT, hints.get(2).toneState());
        // 意：完全命中
        assertEquals(LetterState.CORRECT, hints.get(3).initialState());
        assertEquals(LetterState.CORRECT, hints.get(3).toneState());
    }

    @Test
    void idiomHintsMarkMisplacedComponents() {
        // 答案 画蛇添足 huà shé tiān zú，猜测 添油加醋 tiān yóu jiā cù
        List<IdiomHint> hints = WordleEvaluator.evaluateIdiom("画蛇添足", "添油加醋", stubPinyin());
        // 添：t/ian 与答案第三字相同（位置不对）→ 声母、韵母 PRESENT；
        // 声调 1 的唯一额度被同位命中的「加」（与答案「添」同为 1 声）消耗 → ABSENT
        assertEquals(LetterState.PRESENT, hints.get(0).charState());
        assertEquals(LetterState.PRESENT, hints.get(0).initialState());
        assertEquals(LetterState.PRESENT, hints.get(0).finalState());
        assertEquals(LetterState.ABSENT, hints.get(0).toneState());
        // 醋：声母 c 不存在；韵母 u 与「足」同位 → CORRECT；声调 4 存在于「画」→ PRESENT
        assertEquals(LetterState.ABSENT, hints.get(3).initialState());
        assertEquals(LetterState.CORRECT, hints.get(3).finalState());
        assertEquals(LetterState.PRESENT, hints.get(3).toneState());
    }

    @Test
    void idiomHintLineRendersGroups() {
        List<IdiomHint> hints = WordleEvaluator.evaluateIdiom("一心一意", "三心二意", stubPinyin());
        String line = WordleEvaluator.idiomHintLine(hints);
        assertEquals("🔤 sān⬜⬜🟩 ｜ xīn🟩🟩🟩 ｜ èr⬜⬜ ｜ yì🟩🟩🟩", line);
    }

    @Test
    void idiomHintLineShowsPlaceholderForMissingPinyin() {
        List<IdiomHint> hints = WordleEvaluator.evaluateIdiom("一心一意", "一心二用", codePoint -> null);
        assertEquals(4, hints.size());
        assertEquals("🔤 ? ｜ ? ｜ ? ｜ ?", WordleEvaluator.idiomHintLine(hints));
    }

    private static PinyinLookup stubPinyin() {
        Map<Integer, Pinyin> table = new HashMap<>();
        table.put((int) '一', new Pinyin("y", "i", 1));
        table.put((int) '心', new Pinyin("x", "in", 1));
        table.put((int) '意', new Pinyin("y", "i", 4));
        table.put((int) '三', new Pinyin("s", "an", 1));
        table.put((int) '二', new Pinyin(null, "er", 4));
        table.put((int) '用', new Pinyin("y", "ong", 4));
        table.put((int) '画', new Pinyin("h", "ua", 4));
        table.put((int) '蛇', new Pinyin("sh", "e", 2));
        table.put((int) '添', new Pinyin("t", "ian", 1));
        table.put((int) '足', new Pinyin("z", "u", 2));
        table.put((int) '油', new Pinyin("y", "ou", 2));
        table.put((int) '加', new Pinyin("j", "ia", 1));
        table.put((int) '醋', new Pinyin("c", "u", 4));
        return table::get;
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
