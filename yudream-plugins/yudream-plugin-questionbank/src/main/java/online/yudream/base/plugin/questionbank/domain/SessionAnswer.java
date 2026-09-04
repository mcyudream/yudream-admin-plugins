package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户对会话中某题的作答。按题型取用：SINGLE/TRUE_FALSE 用 choice；MULTIPLE 用 choices；
 * FILL 用 blanks（与题目 blanks 按位对应）；SHORT 用 text。
 * correct 为 null 表示未判（作答中或简答待自评）。
 */
public record SessionAnswer(
        String questionId,
        String choice,
        List<String> choices,
        List<String> blanks,
        String text,
        Boolean correct
) {
    public boolean answered() {
        return (choice != null && !choice.isBlank())
                || (choices != null && !choices.isEmpty())
                || (blanks != null && blanks.stream().anyMatch(item -> item != null && !item.isBlank()))
                || (text != null && !text.isBlank());
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("questionId", questionId);
        DocValues.put(doc, "choice", choice);
        doc.put("choices", choices == null ? List.of() : List.copyOf(choices));
        doc.put("blanks", blanks == null ? List.of() : List.copyOf(blanks));
        DocValues.put(doc, "text", text);
        DocValues.put(doc, "correct", correct);
        return doc;
    }

    public static SessionAnswer fromDoc(Map<String, Object> doc) {
        return new SessionAnswer(
                DocValues.str(doc, "questionId"),
                DocValues.str(doc, "choice"),
                DocValues.stringList(doc, "choices"),
                DocValues.stringList(doc, "blanks"),
                DocValues.str(doc, "text"),
                doc.containsKey("correct") ? DocValues.bool(doc, "correct", false) : null
        );
    }
}
