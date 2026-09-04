package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次抽题练习会话。questions 为题目快照（见 SessionQuestion）；answers 与 questions 通过 questionId 对齐。
 * submittedAt 为 0 表示进行中。correctCount 只统计已判对题数（简答自评后才计入）。
 */
public record PracticeSession(
        String id,
        String userId,
        String userName,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        int requestedCount,
        List<SessionQuestion> questions,
        List<SessionAnswer> answers,
        String status,
        int correctCount,
        long createdAt,
        long submittedAt,
        String paperId,
        String paperName,
        String subjectiveMode
) {
    public static final String STATUS_ONGOING = "ONGOING";
    public static final String STATUS_FINISHED = "FINISHED";

    /** 主观题评分方式与 Paper.subjectiveMode 对齐；历史会话缺省按 SELF 处理。 */
    public boolean reviewMode() {
        return Paper.SUBJECTIVE_REVIEW.equals(subjectiveMode);
    }

    /** AI 判分模式：提交后由宿主 AI 能力异步判分，失败回落人工审核。 */
    public boolean aiMode() {
        return Paper.SUBJECTIVE_AI.equals(subjectiveMode);
    }

    public boolean ongoing() {
        return STATUS_ONGOING.equals(status);
    }

    public int totalCount() {
        return questions == null ? 0 : questions.size();
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("userId", userId);
        DocValues.put(doc, "userName", userName);
        DocValues.put(doc, "categoryId", categoryId);
        doc.put("tags", tags == null ? List.of() : List.copyOf(tags));
        doc.put("types", types == null ? List.of() : List.copyOf(types));
        doc.put("difficulties", difficulties == null ? List.of() : List.copyOf(difficulties));
        doc.put("requestedCount", requestedCount);
        doc.put("questions", questions == null ? List.of() : questions.stream().map(SessionQuestion::toDoc).toList());
        doc.put("answers", answers == null ? List.of() : answers.stream().map(SessionAnswer::toDoc).toList());
        doc.put("status", status);
        doc.put("correctCount", correctCount);
        doc.put("createdAt", createdAt);
        doc.put("submittedAt", submittedAt);
        DocValues.put(doc, "paperId", paperId);
        DocValues.put(doc, "paperName", paperName);
        DocValues.put(doc, "subjectiveMode", subjectiveMode);
        return doc;
    }

    @SuppressWarnings("unchecked")
    public static PracticeSession fromDoc(Map<String, Object> doc) {
        Object questions = doc.get("questions");
        List<SessionQuestion> questionList = questions instanceof List<?> list
                ? list.stream()
                        .filter(item -> item instanceof Map<?, ?>)
                        .map(item -> SessionQuestion.fromDoc((Map<String, Object>) item))
                        .toList()
                : List.of();
        Object answers = doc.get("answers");
        List<SessionAnswer> answerList = answers instanceof List<?> list
                ? list.stream()
                        .filter(item -> item instanceof Map<?, ?>)
                        .map(item -> SessionAnswer.fromDoc((Map<String, Object>) item))
                        .toList()
                : List.of();
        return new PracticeSession(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "userId", ""),
                DocValues.str(doc, "userName"),
                DocValues.str(doc, "categoryId"),
                DocValues.stringList(doc, "tags"),
                DocValues.stringList(doc, "types"),
                DocValues.intList(doc, "difficulties"),
                DocValues.integer(doc, "requestedCount"),
                questionList,
                answerList,
                DocValues.strOr(doc, "status", STATUS_FINISHED),
                DocValues.integer(doc, "correctCount"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "submittedAt"),
                DocValues.str(doc, "paperId"),
                DocValues.str(doc, "paperName"),
                DocValues.str(doc, "subjectiveMode")
        );
    }
}
