package online.yudream.base.plugin.wordle.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wordle 判定器。两遍法保证重复字母不会被误判：
 * 先标出所有位置完全正确的格子，再用剩余答案字符的出现次数决定 PRESENT / ABSENT。
 * 对英文按字母、对成语按汉字（code point）处理，逻辑一致。
 */
public final class WordleEvaluator {

    private WordleEvaluator() {
    }

    public static List<LetterState> evaluate(String answer, String guess) {
        int[] answerChars = answer.codePoints().toArray();
        int[] guessChars = guess.codePoints().toArray();
        int length = answerChars.length;
        LetterState[] states = new LetterState[length];
        Map<Integer, Integer> remaining = new HashMap<>();
        for (int i = 0; i < length; i++) {
            if (i < guessChars.length && guessChars[i] == answerChars[i]) {
                states[i] = LetterState.CORRECT;
            } else {
                remaining.merge(answerChars[i], 1, Integer::sum);
            }
        }
        for (int i = 0; i < length; i++) {
            if (states[i] == LetterState.CORRECT) {
                continue;
            }
            if (i >= guessChars.length) {
                states[i] = LetterState.ABSENT;
                continue;
            }
            int count = remaining.getOrDefault(guessChars[i], 0);
            if (count > 0) {
                states[i] = LetterState.PRESENT;
                remaining.put(guessChars[i], count - 1);
            } else {
                states[i] = LetterState.ABSENT;
            }
        }
        return List.of(states);
    }

    public static String tiles(List<LetterState> states) {
        StringBuilder builder = new StringBuilder();
        for (LetterState state : states) {
            switch (state) {
                case CORRECT -> builder.append("🟩");
                case PRESENT -> builder.append("🟨");
                case ABSENT -> builder.append("⬜");
            }
        }
        return builder.toString();
    }

    public static boolean isSolved(List<LetterState> states) {
        for (LetterState state : states) {
            if (state != LetterState.CORRECT) {
                return false;
            }
        }
        return true;
    }

    /**
     * 困难模式校验：已确定的绿色位置必须保持不变，所有已出现的黄色字母必须继续使用。
     * 返回 null 表示合法，否则返回违规原因。
     */
    public static String checkHardMode(WordleGame game, String guess) {
        int[] guessChars = guess.codePoints().toArray();
        int[] answerChars = game.getAnswer().codePoints().toArray();
        Map<Integer, Integer> required = new HashMap<>();
        for (Guess previous : game.getGuesses()) {
            int[] previousChars = previous.word().codePoints().toArray();
            for (int i = 0; i < previous.states().size() && i < answerChars.length; i++) {
                if (previous.states().get(i) == LetterState.CORRECT) {
                    if (i >= guessChars.length || guessChars[i] != answerChars[i]) {
                        return "困难模式：第 " + (i + 1) + " 位必须是「" + new String(answerChars, i, 1) + "」";
                    }
                } else if (previous.states().get(i) == LetterState.PRESENT) {
                    required.merge(previousChars[i], 1, Integer::sum);
                }
            }
        }
        Map<Integer, Integer> guessCount = new HashMap<>();
        for (int ch : guessChars) {
            guessCount.merge(ch, 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : required.entrySet()) {
            if (guessCount.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return "困难模式：必须包含已提示的「" + new String(Character.toChars(entry.getKey())) + "」";
            }
        }
        return null;
    }

    public static boolean isValidGuess(WordleMode mode, String word, int length) {
        if (word == null || word.isBlank()) {
            return false;
        }
        int[] chars = word.codePoints().toArray();
        if (chars.length != length) {
            return false;
        }
        if (mode == WordleMode.ENGLISH) {
            for (int ch : chars) {
                if (ch < 'a' || ch > 'z') {
                    return false;
                }
            }
            return true;
        }
        for (int ch : chars) {
            if (!isCjk(ch)) {
                return false;
            }
        }
        return true;
    }

    public static String normalize(WordleMode mode, String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        return mode == WordleMode.ENGLISH ? value.toLowerCase(java.util.Locale.ROOT) : value;
    }

    public static List<String> renderBoard(WordleGame game) {
        List<String> lines = new ArrayList<>();
        for (Guess guess : game.getGuesses()) {
            lines.add(guess.tiles() + "  " + guess.word());
        }
        return lines;
    }

    private static boolean isCjk(int ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
    }
}
