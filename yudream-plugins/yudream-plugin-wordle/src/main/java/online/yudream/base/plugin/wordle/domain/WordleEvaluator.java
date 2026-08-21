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
            builder.append(square(state));
        }
        return builder.toString();
    }

    /**
     * 成语模式音节判定：汉字本身沿用逐字规则，声母、韵母、声调各自独立做两遍法匹配。
     * 猜测字或答案字缺拼音数据时，该字所有音节状态记 ABSENT 且提示中展示占位符。
     */
    public static List<IdiomHint> evaluateIdiom(String answer, String guess, PinyinLookup lookup) {
        List<LetterState> charStates = evaluate(answer, guess);
        int length = charStates.size();
        List<Pinyin> answerPinyin = lookup.ofWord(answer);
        List<Pinyin> guessPinyin = lookup.ofWord(guess);
        LetterState[] initialStates = matchComponents(component(answerPinyin, length, Part.INITIAL),
                component(guessPinyin, length, Part.INITIAL), length);
        LetterState[] finalStates = matchComponents(component(answerPinyin, length, Part.FINAL),
                component(guessPinyin, length, Part.FINAL), length);
        LetterState[] toneStates = matchComponents(component(answerPinyin, length, Part.TONE),
                component(guessPinyin, length, Part.TONE), length);
        String[] chars = guess.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toArray(String[]::new);
        List<IdiomHint> hints = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            hints.add(new IdiomHint(i < chars.length ? chars[i] : "",
                    charStates.get(i), i < guessPinyin.size() ? guessPinyin.get(i) : null,
                    initialStates[i], finalStates[i], toneStates[i]));
        }
        return List.copyOf(hints);
    }

    /**
     * 成语猜测的文本音节提示行，例如：🔤 sān⬜⬜🟩 ｜ xīn🟩🟩🟩 ｜ èr⬜⬜ ｜ yì🟩🟩🟩
     * 音节以带调号的标准拼音展示；色块依次对应声母（零声母省略）、韵母、声调。
     */
    public static String idiomHintLine(List<IdiomHint> hints) {
        List<String> groups = new ArrayList<>();
        for (IdiomHint hint : hints) {
            Pinyin pinyin = hint.pinyin();
            if (pinyin == null) {
                groups.add("?");
                continue;
            }
            StringBuilder group = new StringBuilder(pinyin.markedSyllable());
            if (pinyin.hasInitial()) {
                group.append(square(hint.initialState()));
            }
            group.append(square(hint.finalState())).append(square(hint.toneState()));
            groups.add(group.toString());
        }
        return "🔤 " + String.join(" ｜ ", groups);
    }

    private enum Part { INITIAL, FINAL, TONE }

    private static List<String> component(List<Pinyin> pinyin, int length, Part part) {
        List<String> components = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            Pinyin p = i < pinyin.size() ? pinyin.get(i) : null;
            if (p == null) {
                components.add(null);
            } else {
                components.add(switch (part) {
                    case INITIAL -> p.hasInitial() ? p.initial() : null;
                    case FINAL -> p.finalPart();
                    case TONE -> String.valueOf(p.tone());
                });
            }
        }
        return components;
    }

    /**
     * 与逐字判定相同的两遍法：先标同位置完全命中，再按答案剩余额度决定 PRESENT / ABSENT。
     * null 分量（零声母或无拼音数据）不参与匹配。
     */
    private static LetterState[] matchComponents(List<String> answerComp, List<String> guessComp, int length) {
        LetterState[] states = new LetterState[length];
        Map<String, Integer> remaining = new HashMap<>();
        for (int i = 0; i < length; i++) {
            String g = i < guessComp.size() ? guessComp.get(i) : null;
            String a = answerComp.get(i);
            if (g != null && g.equals(a)) {
                states[i] = LetterState.CORRECT;
            } else if (a != null) {
                remaining.merge(a, 1, Integer::sum);
            }
        }
        for (int i = 0; i < length; i++) {
            if (states[i] == LetterState.CORRECT) {
                continue;
            }
            String g = i < guessComp.size() ? guessComp.get(i) : null;
            int count = g == null ? 0 : remaining.getOrDefault(g, 0);
            if (count > 0) {
                states[i] = LetterState.PRESENT;
                remaining.put(g, count - 1);
            } else {
                states[i] = LetterState.ABSENT;
            }
        }
        return states;
    }

    private static String square(LetterState state) {
        return switch (state) {
            case CORRECT -> "🟩";
            case PRESENT -> "🟨";
            case ABSENT -> "⬜";
        };
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

    public static List<String> renderBoard(WordleGame game, PinyinLookup lookup) {
        List<String> lines = new ArrayList<>();
        for (Guess guess : game.getGuesses()) {
            lines.add(guess.tiles() + "  " + guess.word());
            if (game.getMode() == WordleMode.IDIOM && lookup != null) {
                lines.add(idiomHintLine(evaluateIdiom(game.getAnswer(), guess.word(), lookup)));
            }
        }
        return lines;
    }

    private static boolean isCjk(int ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
    }
}
