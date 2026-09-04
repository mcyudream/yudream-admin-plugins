package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话内嵌的题目快照：抽题时固化题干/选项/答案/解析，
 * 历史成绩在题目后续被编辑或删除后仍可完整查看与判分。
 */
public record SessionQuestion(
        String questionId,
        String type,
        String content,
        List<String> options,
        String answer,
        List<String> answers,
        List<List<String>> blanks,
        String referenceAnswer,
        String analysis,
        int difficulty
) {
    public static SessionQuestion snapshotOf(Question question) {
        return new SessionQuestion(
                question.id(),
                question.type().name(),
                question.content(),
                question.options() == null ? List.of() : List.copyOf(question.options()),
                question.answer(),
                question.answers() == null ? List.of() : List.copyOf(question.answers()),
                question.blanks() == null ? List.of() : question.blanks().stream().map(List::copyOf).toList(),
                question.referenceAnswer(),
                question.analysis(),
                question.difficulty()
        );
    }

    public QuestionType questionType() {
        return QuestionType.from(type);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("questionId", questionId);
        doc.put("type", type);
        doc.put("content", content == null ? "" : content);
        doc.put("options", options == null ? List.of() : List.copyOf(options));
        DocValues.put(doc, "answer", answer);
        doc.put("answers", answers == null ? List.of() : List.copyOf(answers));
        doc.put("blanks", blanks == null ? List.of() : blanks.stream().map(List::copyOf).toList());
        DocValues.put(doc, "referenceAnswer", referenceAnswer);
        DocValues.put(doc, "analysis", analysis);
        doc.put("difficulty", difficulty);
        return doc;
    }

    public static SessionQuestion fromDoc(Map<String, Object> doc) {
        return new SessionQuestion(
                DocValues.str(doc, "questionId"),
                DocValues.strOr(doc, "type", "SINGLE"),
                DocValues.strOr(doc, "content", ""),
                DocValues.stringList(doc, "options"),
                DocValues.str(doc, "answer"),
                DocValues.stringList(doc, "answers"),
                DocValues.stringListList(doc, "blanks"),
                DocValues.str(doc, "referenceAnswer"),
                DocValues.str(doc, "analysis"),
                DocValues.integerOr(doc, "difficulty", 3)
        );
    }
}
