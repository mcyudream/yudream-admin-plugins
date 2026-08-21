package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessGame;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentPlayerRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 猜物群回合制生命周期：自动开局 → 猜测 → 胜利结算 → 立即再开新局 → 投降。
 * 存储使用与宿主沙盒一致的严格假实现（Map.copyOf 遇 null 抛 NPE）。
 */
class McguessAppServiceTest {

    private McguessDocumentGameRepository games;
    private McguessDocumentPlayerRepository players;
    private McCatalog catalog;
    private McguessAppService service;

    @BeforeEach
    void setUp() {
        PluginDocumentStore store = new SandboxStore();
        games = new McguessDocumentGameRepository(store);
        players = new McguessDocumentPlayerRepository(store);
        catalog = McDataLoader.load(getClass().getClassLoader());
        // 昵称解析失败不影响战绩记录，框架服务传 null 即可
        McguessSupport support = new McguessSupport(players, null);
        IconSupport icons = new IconSupport(getClass().getClassLoader());
        service = new McguessAppService(games, catalog, icons, support);
    }

    private static PluginEvent groupEvent(String qq) {
        return new PluginEvent(null, "message", "qq", qq, "20001", null, null, null,
                Map.of(), null, null, "conn-1", "self", null);
    }

    private String activeTargetZh() {
        McguessGame game = games.findActive("conn-1", "20001").orElseThrow();
        return catalog.byId(game.getTargetId()).map(McItem::zh).orElseThrow();
    }

    @Test
    void guessAutoStartsRoundAndWinSettlesStats() {
        PluginEvent event = groupEvent("10001");
        String first = service.guess(event, 1L, "泥土");
        assertTrue(first.contains("泥土"), "首次猜测应自动开局并给出判定");
        McguessGame started = games.findActive("conn-1", "20001").orElseThrow();
        assertEquals(McguessGame.STATUS_PLAYING, started.getStatus());
        assertEquals("10001", started.getStartedByQq());

        String reply = service.guess(event, 1L, activeTargetZh());
        assertTrue(reply.contains("🎉"), "猜中目标应回复胜利");
        assertTrue(games.findActive("conn-1", "20001").isEmpty(), "终局后不再有进行中对局");
        McguessGame ended = games.findLatest("conn-1", "20001").orElseThrow();
        assertEquals(McguessGame.STATUS_WON, ended.getStatus());
        assertEquals("10001", ended.getWinnerQq());

        McguessPlayer player = players.findByUserId("1").orElseThrow();
        assertEquals(1, player.getItemPlayed());
        assertEquals(1, player.getItemWins());
        assertEquals(2, player.getTotalGuesses());
    }

    @Test
    void newRoundStartsImmediatelyAfterWin() {
        PluginEvent event = groupEvent("10001");
        service.guess(event, 1L, "泥土");
        String previousId = games.findActive("conn-1", "20001").orElseThrow().getId();
        service.guess(event, 1L, activeTargetZh());

        service.guess(event, 2L, "石头");
        McguessGame next = games.findActive("conn-1", "20001").orElseThrow();
        assertNotEquals(previousId, next.getId(), "终局后下一次猜测应开启新一局");
        assertEquals(McguessGame.STATUS_PLAYING, next.getStatus());
        assertEquals("2", next.getStartedByUserId());
    }

    @Test
    void surrenderLosesRoundAndRecordsParticipation() {
        PluginEvent event = groupEvent("10001");
        service.guess(event, 1L, "泥土");
        String reply = service.surrender(event);
        assertTrue(reply.contains("🏳️"));
        McguessGame ended = games.findLatest("conn-1", "20001").orElseThrow();
        assertEquals(McguessGame.STATUS_LOST, ended.getStatus());

        McguessPlayer player = players.findByUserId("1").orElseThrow();
        assertEquals(1, player.getItemPlayed());
        assertEquals(0, player.getItemWins(), "投降不记胜场");

        assertTrue(service.surrender(event).contains("没有进行中"), "无局时投降应提示");
    }

    @Test
    void hintRequiresActiveGameAndStreak() {
        PluginEvent event = groupEvent("10001");
        assertTrue(service.hint(event).contains("没有进行中"), "无局时提示不应自动开局");
        assertTrue(games.findActive("conn-1", "20001").isEmpty());

        service.guess(event, 1L, "泥土");
        String early = service.hint(event);
        assertTrue(early.contains("还没有可用的提示"));
    }

    @Test
    void anonymousGuessDoesNotRecordStats() {
        PluginEvent event = groupEvent("10002");
        service.guess(event, null, "泥土");
        service.guess(event, null, activeTargetZh());
        assertTrue(players.findByUserId("10002").isEmpty(), "QQ 号不等于绑定账号，不应产生战绩");
        assertEquals(0, players.count());
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
