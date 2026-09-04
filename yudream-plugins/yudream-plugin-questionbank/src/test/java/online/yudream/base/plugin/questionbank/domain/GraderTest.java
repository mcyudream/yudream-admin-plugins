package online.yudream.base.plugin.questionbank.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 判分器：单选/多选/判断/填空/简答的判定口径。 */
class GraderTest {

    private SessionQuestion question(QuestionType type, String answer, List<String> answers, List<List<String>> blanks) {
        return new SessionQuestion("q1", type.name(), "题干", List.of("甲", "乙", "丙"),
                answer, answers, blanks, null, null, 3);
    }

    @Test
    void singleChoiceMatchesKeyCaseInsensitive() {
        SessionQuestion q = question(QuestionType.SINGLE, "B", List.of(), List.of());
        assertTrue(Grader.grade(q, new SessionAnswer("q1", "b", List.of(), List.of(), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", "A", List.of(), List.of(), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of(), null, null)));
    }

    @Test
    void multipleChoiceRequiresExactSet() {
        SessionQuestion q = question(QuestionType.MULTIPLE, null, List.of("A", "C"), List.of());
        assertTrue(Grader.grade(q, new SessionAnswer("q1", null, List.of("c", "a"), List.of(), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", null, List.of("A"), List.of(), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", null, List.of("A", "C", "B"), List.of(), null, null)));
    }

    @Test
    void fillBlanksTrimAndCaseInsensitivePerBlank() {
        SessionQuestion q = question(QuestionType.FILL, null, List.of(),
                List.of(List.of("Hello", "你好"), List.of("World")));
        assertTrue(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of(" hello ", "world"), null, null)));
        assertTrue(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of("你好", "World"), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of("hello"), null, null)));
        assertFalse(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of("hello", "bad"), null, null)));
    }

    @Test
    void shortAnswerWaitsForSelfMark() {
        SessionQuestion q = question(QuestionType.SHORT, null, List.of(), List.of());
        assertNull(Grader.grade(q, new SessionAnswer("q1", null, List.of(), List.of(), "我的回答", null)));
    }

    @Test
    void unansweredObjectiveIsWrong() {
        SessionQuestion q = question(QuestionType.TRUE_FALSE, "TRUE", List.of(), List.of());
        assertFalse(Grader.grade(q, null));
    }

    @Test
    void typeFromRejectsUnknown() {
        assertEquals(QuestionType.SINGLE, QuestionType.from("single"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> QuestionType.from("X"));
    }
}
