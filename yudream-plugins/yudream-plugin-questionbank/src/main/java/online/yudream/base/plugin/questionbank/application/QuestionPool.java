package online.yudream.base.plugin.questionbank.application;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import online.yudream.base.plugin.questionbank.domain.Question;

/** 抽题池过滤：自由练习与题单（RULE 模式）共用的题目筛选逻辑。空条件不参与过滤。 */
public final class QuestionPool {
    private QuestionPool() {
    }

    /**
     * 抽题排除项：不入池的分类与题型（题型为枚举名，大小写不敏感）。
     *
     * <p>null 或空列表表示该维度不排除。排除优先于包含——同一分类/题型既出现在包含条件里又出现在排除
     * 条件里时不入池。未分类（{@code categoryId} 为空）的题目不受分类排除影响。
     */
    public record Exclusions(List<String> categoryIds, List<String> types) {
        public static final Exclusions NONE = new Exclusions(List.of(), List.of());

        public Exclusions {
            categoryIds = categoryIds == null ? List.of()
                    : categoryIds.stream().filter(Objects::nonNull).toList();
            types = types == null ? List.of()
                    : types.stream().filter(Objects::nonNull).toList();
        }
    }

    public static List<Question> filter(List<Question> questions, String categoryId,
            List<String> tags, List<String> types, List<Integer> difficulties) {
        return filter(questions, categoryId, tags, types, difficulties, Exclusions.NONE);
    }

    /** 带排除项的抽题池过滤：先按正向条件选池，再剔除排除的分类与题型。 */
    public static List<Question> filter(List<Question> questions, String categoryId,
            List<String> tags, List<String> types, List<Integer> difficulties, Exclusions exclusions) {
        Exclusions effective = exclusions == null ? Exclusions.NONE : exclusions;
        Set<String> excludedCategories = new HashSet<>();
        for (String excludedId : effective.categoryIds()) {
            if (excludedId != null && !excludedId.isBlank()) {
                excludedCategories.add(excludedId.trim());
            }
        }
        Set<String> excludedTypes = new HashSet<>();
        for (String excludedType : effective.types()) {
            if (excludedType != null && !excludedType.isBlank()) {
                excludedTypes.add(excludedType.trim().toUpperCase(Locale.ROOT));
            }
        }
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
                .filter(question -> excludedCategories.isEmpty() || question.categoryId() == null
                        || !excludedCategories.contains(question.categoryId()))
                .filter(question -> excludedTypes.isEmpty() || !excludedTypes.contains(question.type().name()))
                .toList();
    }
}
