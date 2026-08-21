package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快答出题器：题量、选项合法性与答案正确性（基于真实数据集）。
 */
class QuizGeneratorTest {

    private static McCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.load(QuizGeneratorTest.class.getClassLoader());
    }

    @Test
    void generatesFiveDistinctTargetQuestions() {
        QuizGenerator generator = new QuizGenerator(catalog);
        List<QuizGame.Question> questions = generator.generate(new Random(20260822L));
        assertEquals(QuizGame.QUESTION_COUNT, questions.size());
        assertEquals(questions.size(), questions.stream().map(QuizGame.Question::targetId).distinct().count(),
                "每题目标物品不重复");
    }

    @Test
    void choicesContainAnswerAndAreDistinct() {
        QuizGenerator generator = new QuizGenerator(catalog);
        for (long seed = 1; seed <= 5; seed++) {
            List<QuizGame.Question> questions = generator.generate(new Random(seed));
            for (QuizGame.Question question : questions) {
                assertEquals(QuizGame.CHOICE_COUNT, question.choices().size());
                assertEquals(QuizGame.CHOICE_COUNT, new HashSet<>(question.choices()).size(), "选项互不相同");
                assertTrue(question.choices().contains(question.answer()), "选项必须包含答案");
                assertTrue(question.choices().stream().allMatch(choice -> choice > 0), "选项都是正整数");
            }
        }
    }

    @Test
    void answerMatchesCraftingTreeOccurrences() {
        QuizGenerator generator = new QuizGenerator(catalog);
        List<QuizGame.Question> questions = generator.generate(new Random(42L));
        for (QuizGame.Question question : questions) {
            int expected = catalog.treeOf(question.targetId()).occurrencesOf(question.ingredientId());
            assertEquals(expected, question.answer(), "答案应等于原料在目标合成树中的出现次数");
            assertTrue(question.answer() > 0);
            assertNotEquals(question.targetId(), question.ingredientId());
            assertTrue(catalog.byId(question.ingredientId()).map(McItem::icon).orElse(false),
                    "被问原料必须有图标");
        }
    }
}
