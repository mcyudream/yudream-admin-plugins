package online.yudream.base.plugin.questionbank.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 客观题判分。简答（SHORT）不参与自动判分，返回 null 等待用户自评。
 * 未作答的客观题判错。
 */
public final class Grader {
    private Grader() {
    }

    /** 返回 null 表示本题不自动判分（简答待自评）。 */
    public static Boolean grade(SessionQuestion question, SessionAnswer answer) {
        QuestionType type = question.questionType();
        if (type == QuestionType.SHORT) {
            return null;
        }
        if (answer == null) {
            return false;
        }
        return switch (type) {
            case SINGLE -> equalsKey(answer.choice(), question.answer());
            case TRUE_FALSE -> equalsKey(answer.choice(), question.answer());
            case MULTIPLE -> keySet(answer.choices()).equals(keySet(question.answers()));
            case FILL -> gradeFill(question.blanks(), answer.blanks());
            case SHORT -> null;
        };
    }

    private static boolean equalsKey(String given, String expected) {
        return normalizeKey(given).equals(normalizeKey(expected));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> keySet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String key = normalizeKey(value);
            if (!key.isEmpty()) {
                set.add(key);
            }
        }
        return set;
    }

    private static boolean gradeFill(List<List<String>> expectedBlanks, List<String> givenBlanks) {
        if (expectedBlanks == null || expectedBlanks.isEmpty()) {
            return false;
        }
        List<String> given = givenBlanks == null ? List.of() : givenBlanks;
        for (int i = 0; i < expectedBlanks.size(); i++) {
            String input = i < given.size() ? normalizeFill(given.get(i)) : "";
            if (input.isEmpty()) {
                return false;
            }
            List<String> accepted = new ArrayList<>();
            for (String candidate : expectedBlanks.get(i)) {
                accepted.add(normalizeFill(candidate));
            }
            if (!accepted.contains(input)) {
                return false;
            }
        }
        return true;
    }

    /** 填空判定：去首尾空白、忽略大小写。 */
    public static String normalizeFill(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
