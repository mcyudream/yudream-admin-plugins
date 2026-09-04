package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import online.yudream.base.plugin.questionbank.domain.QuestionType;

/**
 * 清洗选择题题干末尾残留的选项文本（AI 导入常把 "A. xx B. xx" 混进题干）。
 * 仅处理 SINGLE/MULTIPLE 且已有独立 options 的题目；清洗结果为空时保留原文，防止误删。
 */
public final class OptionStripper {
    /** 行模式：A. / B、/ C．/ D) / E: 等开头的行（半角/全角字母与分隔符）。 */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^[A-FＡ-Ｆ]\\s*[.、．)）:：]\\s*\\S.*$");
    /** 内联模式：同一行末尾连续 2 个及以上选项标记段，如 "A. 40 B. 39 C. 63 D. 31"。 */
    private static final Pattern INLINE_TAIL = Pattern.compile(
            "(?:^|[\\s　])(?:[A-FＡ-Ｆ]\\s*[.、．)）][\\s　]*\\S[^\\n]*?){2,}$");

    private OptionStripper() {
    }

    public static String strip(String content, QuestionType type, int optionCount) {
        if (content == null || optionCount < 2
                || (type != QuestionType.SINGLE && type != QuestionType.MULTIPLE)) {
            return content;
        }
        String stripped = stripOptionLines(content);
        stripped = stripInlineTail(stripped);
        if (stripped.isBlank()) {
            return content;
        }
        return stripped;
    }

    /** 去掉末尾连续的选项整行。 */
    private static String stripOptionLines(String content) {
        String[] lines = content.split("\\R", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        int start = end;
        while (start > 0 && OPTION_LINE.matcher(lines[start - 1].trim()).matches()) {
            start--;
        }
        // 至少剥离 2 行才算选项段，避免误删单行内容
        if (end - start < 2) {
            return content;
        }
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            kept.add(lines[i]);
        }
        return String.join("\n", kept).stripTrailing();
    }

    /** 去掉末行内部的内联选项尾巴。 */
    private static String stripInlineTail(String content) {
        var matcher = INLINE_TAIL.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        String candidate = content.substring(0, matcher.start()).stripTrailing();
        // 候选必须非空且剥掉的部分确实以连续选项标记构成
        return candidate.isBlank() ? content : candidate;
    }
}
