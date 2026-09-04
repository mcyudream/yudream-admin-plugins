package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.questionbank.infrastructure.FakeFramework;
import online.yudream.base.plugin.questionbank.infrastructure.QuizScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** QQ 抢答积分：累计计数、排行榜排序与分群过滤、未绑定 QQ 号遮蔽。 */
class QuizScoreServiceTest {
    private QuizScoreService service;

    @BeforeEach
    void setUp() {
        service = new QuizScoreService(new QuizScoreRepository(new FakeDocumentStore()), new FakeFramework());
    }

    private static Question question(String id) {
        return new Question(id, QuestionType.SINGLE, null, List.of(), "题干", List.of("甲", "乙"),
                "A", List.of(), List.of(), null, null, 3, Question.STATUS_ENABLED, "1", null, 0, 0);
    }

    @Test
    void recordWinAccumulatesPerQq() {
        assertEquals(1, service.recordWin("10001", "c1", "g1", question("q1")));
        assertEquals(2, service.recordWin("10001", "c1", "g1", question("q2")));
        assertEquals(1, service.recordWin("20002", "c1", "g1", question("q3")));
        assertEquals(2, service.scoreOf("10001"));
        assertEquals(1, service.scoreOf("20002"));
        assertEquals(0, service.scoreOf("99999"));
    }

    @Test
    void leaderboardRanksByCountAndMasksUnboundQq() {
        service.recordWin("10001", "c1", "g1", question("q1"));
        service.recordWin("20002", "c1", "g1", question("q2"));
        service.recordWin("20002", "c1", "g1", question("q3"));
        List<Map<String, Object>> board = service.leaderboard();
        assertEquals(2, board.size());
        assertEquals(1, board.get(0).get("rank"));
        assertEquals(2L, board.get(0).get("score"));
        assertEquals("QQ 200****02", board.get(0).get("name"));
        assertEquals(false, board.get(0).get("bound"));
        assertEquals(2, board.get(1).get("rank"));
        assertFalse(board.get(0).containsKey("qq"));
    }

    @Test
    void channelLeaderboardScopesByChannelAndKeepsRawQq() {
        service.recordWin("10001", "c1", "g1", question("q1"));
        service.recordWin("10001", "c1", "g2", question("q2"));
        service.recordWin("20002", "c2", "g1", question("q3"));
        List<Map<String, Object>> scoped = service.channelLeaderboard("c1", "g1");
        assertEquals(1, scoped.size());
        assertEquals("10001", scoped.get(0).get("name"));
        assertEquals(1L, scoped.get(0).get("score"));
        assertEquals(1, service.channelLeaderboard("c2", "g1").size());
        assertTrue(service.channelLeaderboard("c1", "g3").isEmpty());
    }

    @Test
    void maskQqCoversEdgeCases() {
        assertEquals("未知", QuizScoreService.maskQq(null));
        assertEquals("未知", QuizScoreService.maskQq(" "));
        assertEquals("1***", QuizScoreService.maskQq("123"));
        assertEquals("QQ 123****89", QuizScoreService.maskQq("123456789"));
    }
}
