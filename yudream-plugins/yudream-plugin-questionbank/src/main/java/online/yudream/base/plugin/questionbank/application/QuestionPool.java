package online.yudream.base.plugin.questionbank.application;

import java.util.List;
import java.util.Locale;
import online.yudream.base.plugin.questionbank.domain.Question;

/** 抽题池过滤：自由练习与题单（RULE 模式）共用的题目筛选逻辑。空条件不参与过滤。 */
public final class QuestionPool {
    private QuestionPool() {
    }

    public static List<Question> filter(List<Question> questions, String categoryId,
            List<String> tags, List<String> types, List<Integer> difficulties) {
        List<String> normalizedTypes = types == null ? List.of() : types.stream()
                .map(type -> type.trim().toUpperCase(Locale.ROOT)).toList();
        List<String> normalizedTags = tags == null ? List.of() : tags;
        List<Integer> normalizedDifficulties = difficulties == null ? List.of() : difficulties;
        return questions.stream()
                .filter(question -> categoryId == null || categoryId.isBlank()
                        || categoryId.equals(question.categoryId()))
                .filter(question -> normalizedTags.isEmpty()
                        || (question.tags() != null && question.tags().stream().anyMatch(normalizedTags::contains)))
                .filter(question -> normalizedTypes.isEmpty() || normalizedTypes.contains(question.type().name()))
                .filter(question -> normalizedDifficulties.isEmpty()
                        || normalizedDifficulties.contains(question.difficulty()))
                .toList();
    }
}
