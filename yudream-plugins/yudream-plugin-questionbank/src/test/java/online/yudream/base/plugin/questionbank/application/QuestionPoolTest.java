package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import org.junit.jupiter.api.Test;

/** 抽题池排除项（分类 + 题型）：各自生效、排除优先于包含、未分类题不受影响、空白与重复项忽略。 */
class QuestionPoolTest {

    @Test
    void excludedCategoryDropsOnlyThatCategory() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", "cat-a"), question("2", "cat-b"), question("3", "cat-a")),
                null, List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(List.of("cat-a"), List.of()));
        assertEquals(List.of("2"), pool.stream().map(Question::id).toList());
    }

    @Test
    void excludedTypeDropsOnlyThatType() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", "cat-a", QuestionType.SINGLE), question("2", "cat-a", QuestionType.SHORT)),
                null, List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(List.of(), List.of("SHORT")));
        assertEquals(List.of("1"), pool.stream().map(Question::id).toList());
    }

    @Test
    void bothDimensionsApplyTogether() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", "cat-a", QuestionType.SINGLE),
                        question("2", "cat-b", QuestionType.SINGLE),
                        question("3", "cat-b", QuestionType.SHORT)),
                null, List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(List.of("cat-a"), List.of("SHORT")));
        assertEquals(List.of("2"), pool.stream().map(Question::id).toList());
    }

    @Test
    void exclusionWinsOverInclusion() {
        List<Question> byCategory = QuestionPool.filter(
                List.of(question("1", "cat-a")), "cat-a", List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(List.of("cat-a"), List.of()));
        assertEquals(List.of(), byCategory);
        List<Question> byType = QuestionPool.filter(
                List.of(question("1", "cat-a", QuestionType.SINGLE)),
                null, List.of(), List.of("SINGLE"), List.of(),
                new QuestionPool.Exclusions(List.of(), List.of("SINGLE")));
        assertEquals(List.of(), byType);
    }

    @Test
    void questionsWithoutCategorySurviveCategoryExclusion() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", null), question("2", "cat-a")),
                null, List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(List.of("cat-a"), List.of()));
        assertEquals(List.of("1"), pool.stream().map(Question::id).toList());
    }

    @Test
    void blankDuplicateAndLowercaseEntriesAreNormalized() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", "cat-a", QuestionType.SHORT)),
                null, List.of(), List.of(), List.of(),
                new QuestionPool.Exclusions(Arrays.asList(" cat-a ", "cat-a", "   ", null),
                        Arrays.asList(" short ", "SHORT", "")));
        assertEquals(List.of(), pool);
    }

    @Test
    void emptyAndNullExclusionsExcludeNothing() {
        List<Question> questions = List.of(question("1", "cat-a"));
        assertEquals(List.of("1"), QuestionPool.filter(questions, null, List.of(), List.of(), List.of(),
                QuestionPool.Exclusions.NONE).stream().map(Question::id).toList());
        assertEquals(List.of("1"), QuestionPool.filter(questions, null, List.of(), List.of(), List.of(),
                null).stream().map(Question::id).toList());
    }

    @Test
    void legacyOverloadStillReturnsEverything() {
        List<Question> pool = QuestionPool.filter(
                List.of(question("1", "cat-a")), null, List.of(), List.of(), List.of());
        assertEquals(List.of("1"), pool.stream().map(Question::id).toList());
    }

    private static Question question(String id, String categoryId) {
        return question(id, categoryId, QuestionType.SINGLE);
    }

    private static Question question(String id, String categoryId, QuestionType type) {
        return new Question(id, type, categoryId, List.of(), "题干", List.of("甲", "乙"),
                "A", List.of(), List.of(), null, null, 3, Question.STATUS_ENABLED, "1", "管理员", 1L, 1L);
    }
}
