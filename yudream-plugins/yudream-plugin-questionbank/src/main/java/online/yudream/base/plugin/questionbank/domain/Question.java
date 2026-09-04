package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题目聚合根。id 为「倒置毫秒时间戳-随机hex」，宿主文档存储 _id 字典序升序即最新在前。
 * 题干 content、解析 analysis、简答 referenceAnswer 为 Markdown；选项 options 为纯文本，键为序号字母（A/B/C…）。
 * 答案字段按题型取用：SINGLE/TRUE_FALSE 用 answer；MULTIPLE 用 answers（已排序字母集合）；
 * FILL 用 blanks（每空可接受答案列表）；SHORT 用 referenceAnswer。
 */
public record Question(
        String id,
        QuestionType type,
        String categoryId,
        List<String> tags,
        String content,
        List<String> options,
        String answer,
        List<String> answers,
        List<List<String>> blanks,
        String referenceAnswer,
        String analysis,
        int difficulty,
        String status,
        String createdBy,
        String createdByName,
        long createdAt,
        long updatedAt
) {
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    public static String optionKey(int index) {
        return String.valueOf((char) ('A' + index));
    }

    public boolean enabled() {
        return STATUS_ENABLED.equals(status);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", type.name());
        DocValues.put(doc, "categoryId", categoryId);
        doc.put("tags", tags == null ? List.of() : List.copyOf(tags));
        doc.put("content", content);
        doc.put("options", options == null ? List.of() : List.copyOf(options));
        DocValues.put(doc, "answer", answer);
        doc.put("answers", answers == null ? List.of() : List.copyOf(answers));
        doc.put("blanks", blanks == null ? List.of() : blanks.stream().map(List::copyOf).toList());
        DocValues.put(doc, "referenceAnswer", referenceAnswer);
        DocValues.put(doc, "analysis", analysis);
        doc.put("difficulty", difficulty);
        doc.put("status", status);
        DocValues.put(doc, "createdBy", createdBy);
        DocValues.put(doc, "createdByName", createdByName);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static Question fromDoc(Map<String, Object> doc) {
        return new Question(
                DocValues.str(doc, "id"),
                QuestionType.from(DocValues.strOr(doc, "type", "SINGLE")),
                DocValues.str(doc, "categoryId"),
                DocValues.stringList(doc, "tags"),
                DocValues.strOr(doc, "content", ""),
                DocValues.stringList(doc, "options"),
                DocValues.str(doc, "answer"),
                DocValues.stringList(doc, "answers"),
                DocValues.stringListList(doc, "blanks"),
                DocValues.str(doc, "referenceAnswer"),
                DocValues.str(doc, "analysis"),
                DocValues.integerOr(doc, "difficulty", 3),
                DocValues.strOr(doc, "status", STATUS_ENABLED),
                DocValues.str(doc, "createdBy"),
                DocValues.str(doc, "createdByName"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt")
        );
    }
}
