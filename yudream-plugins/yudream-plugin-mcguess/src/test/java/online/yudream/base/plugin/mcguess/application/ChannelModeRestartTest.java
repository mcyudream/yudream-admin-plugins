package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.QuizGame;
import online.yudream.base.plugin.mcguess.domain.SpotGame;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentPlayerRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentQuizGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentSpotGameRepository;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群模式裸指令回归：上一局结束后再次发送裸指令（/找茬、/快答 ……）必须直接开新局，
 * 而不是回显已结束的旧局；快答棋盘渲染变量必须始终携带 choices 键——宿主模板引擎
 * 是 SpringEL，读取 Map 缺失键会直接抛异常，只有「键存在、值为 null」才能安全判空。
 * 存储使用与宿主沙盒一致的严格假实现（Map.copyOf 遇 null 抛 NPE）。
 */
class ChannelModeRestartTest {

    private McCatalog catalog;
    private McguessSupport support;
    private IconSupport icons;
    private McguessDocumentSpotGameRepository spotGames;
    private McguessDocumentQuizGameRepository quizGames;
    private SpotAppService spotService;
    private QuizAppService quizService;

    @BeforeEach
    void setUp() {
        PluginDocumentStore store = new SandboxStore();
        spotGames = new McguessDocumentSpotGameRepository(store);
        quizGames = new McguessDocumentQuizGameRepository(store);
        McguessDocumentPlayerRepository players = new McguessDocumentPlayerRepository(store);
        catalog = McDataLoader.load(getClass().getClassLoader());
        support = new McguessSupport(players, null);
        icons = new IconSupport(getClass().getClassLoader());
        spotService = new SpotAppService(spotGames, catalog, icons, support);
        quizService = new QuizAppService(quizGames, catalog, icons, support);
    }

    private static PluginEvent groupEvent(String qq) {
        return new PluginEvent(null, "message", "qq", qq, "20001", null, null, null,
                Map.of(), null, null, "conn-1", "self", null);
    }

    @Test
    void spotBareCommandAfterEndStartsNewRound() {
        PluginEvent event = groupEvent("10001");
        spotService.answer(event, 1L, null);
        SpotGame first = spotGames.findActive("conn-1", "20001").orElseThrow();
        spotService.surrender(event);
        assertTrue(spotGames.findActive("conn-1", "20001").isEmpty(), "投降后不再有进行中对局");

        String reply = spotService.answer(event, 1L, null);
        assertTrue(reply.contains("🎬 新一局开始"), "结束后裸指令应直接开新局而不是回显旧局");
        assertTrue(reply.contains("找茬进行中"));
        SpotGame next = spotGames.findActive("conn-1", "20001").orElseThrow();
        assertNotEquals(first.getId(), next.getId(), "应是一局新游戏");
        assertEquals(SpotGame.STATUS_PLAYING, next.getStatus());
    }

    @Test
    void quizBareCommandAfterEndStartsNewRound() {
        PluginEvent event = groupEvent("10001");
        quizService.answer(event, 1L, null);
        QuizGame first = quizGames.findActive("conn-1", "20001").orElseThrow();
        quizService.surrender(event);

        String reply = quizService.answer(event, 1L, null);
        assertTrue(reply.contains("🎬 新一局开始"), "结束后裸指令应直接开新局而不是回显旧局");
        QuizGame next = quizGames.findActive("conn-1", "20001").orElseThrow();
        assertNotEquals(first.getId(), next.getId(), "应是一局新游戏");
        assertEquals(QuizGame.STATUS_PLAYING, next.getStatus());
    }

    @Test
    void quizBoardVariablesAlwaysExposeChoicesKey() {
        PluginEvent event = groupEvent("10001");
        quizService.answer(event, 1L, null);

        // 进行中：仅当前题展示选项，其余题 choices 为 null，但每一行都必须有 choices 键
        assertAllRowsExposeChoicesKey();

        // 答对第一题后，已解答的过去行同样必须带 choices 键（此前缺失导致模板渲染崩溃）
        QuizGame game = quizGames.findActive("conn-1", "20001").orElseThrow();
        int index = game.currentQuestionIndex();
        QuizGame.Question question = game.getQuestions().get(index);
        String letter = List.of("A", "B", "C", "D").get(question.choices().indexOf(question.answer()));
        String reply = quizService.answer(event, 1L, letter);
        assertTrue(reply.contains("✅"), "按正确答案作答应判对：" + reply);
        assertAllRowsExposeChoicesKey();

        // 投降后的终局棋盘：未答出的题会补展示选项，所有行仍必须有 choices 键
        quizService.surrender(event);
        Map<String, Object> variables = quizService.boardVariables(event, null);
        assertNotNull(variables);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) variables.get("questions");
        QuizGame ended = quizGames.findLatest("conn-1", "20001").orElseThrow();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            assertTrue(row.containsKey("choices"), "第 " + (i + 1) + " 题行缺少 choices 键");
            if (ended.isSolved(i)) {
                assertNull(row.get("choices"), "已答出的题终局不展示选项");
            } else {
                assertNotNull(row.get("choices"), "未答出的题终局应补展示选项");
            }
        }
    }

    private void assertAllRowsExposeChoicesKey() {
        Map<String, Object> variables = quizService.boardVariables(groupEvent("10001"), null);
        assertNotNull(variables);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) variables.get("questions");
        assertEquals(QuizGame.QUESTION_COUNT, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertTrue(rows.get(i).containsKey("choices"),
                    "第 " + (i + 1) + " 题行缺少 choices 键，SpringEL 模板会直接抛异常");
        }
    }

    /** 与宿主 SandboxAwarePluginDocumentStore 行为一致：save 时 Map.copyOf，遇 null 抛 NPE。 */
    private static final class SandboxStore implements PluginDocumentStore {

        private final Map<String, Map<String, Map<String, Object>>> collections = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            collections.computeIfAbsent(collection, ignored -> new HashMap<>()).put(id, Map.copyOf(document));
            return document;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> all = new ArrayList<>(collections.getOrDefault(collection, Map.of()).values());
            int from = Math.min(Math.max(0, page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> doc : collections.getOrDefault(collection, Map.of()).values()) {
                if (Objects.equals(doc.get(field), value)) {
                    matched.add(doc);
                }
            }
            int from = Math.min(Math.max(0, page - 1) * size, matched.size());
            return matched.subList(from, Math.min(from + size, matched.size()));
        }

        @Override
        public long count(String collection) {
            return collections.getOrDefault(collection, Map.of()).size();
        }

        @Override
        public void delete(String collection, String id) {
            Map<String, Map<String, Object>> docs = collections.get(collection);
            if (docs != null) {
                docs.remove(id);
            }
        }
    }
}
