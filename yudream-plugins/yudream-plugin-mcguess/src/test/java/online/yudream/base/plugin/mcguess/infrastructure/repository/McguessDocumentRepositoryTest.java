package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.McguessGame;
import online.yudream.base.plugin.mcguess.domain.McguessGame.McGuess;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.domain.MobGame;
import online.yudream.base.plugin.mcguess.domain.MobGame.MobGuess;
import online.yudream.base.plugin.mcguess.domain.RecipeGame;
import online.yudream.base.plugin.mcguess.domain.RecipeGame.RecipeGuess;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三种模式仓储的 round-trip：宿主沙盒 overlay 存储会用 Map.copyOf 复制文档，
 * null 值直接抛 NPE；这里用同样严格的存储回归验证仓储写入前已移除 null 值，
 * 且格子中的 null（序列化为 ""）读回后正确还原，旧文档缺字段时按默认值宽容读取。
 */
class McguessDocumentRepositoryTest {

    // ---------------------------------------------------------------- 猜物

    @Test
    void itemGameWithNullFieldsSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        McguessDocumentGameRepository repo = new McguessDocumentGameRepository(store);
        McguessGame game = new McguessGame("g1", "conn-1", null, "20001", "diamond_sword", null, null, 1000L);
        game.addGuess(new McGuess("泥土", "dirt", "泥土", McGuess.RESULT_MISS, null, 0, "10001", null, 1100L));

        repo.save(game);

        McguessGame reloaded = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals("g1", reloaded.getId());
        assertNull(reloaded.getPlatform());
        assertNull(reloaded.getWinnerQq());
        assertEquals(1, reloaded.getGuesses().size());
        assertNull(reloaded.getGuesses().get(0).distance(), "不可达猜测的 distance 应读回为 null");
        assertNull(reloaded.getGuesses().get(0).userId());
    }

    @Test
    void findLatestReturnsMostRecentItemGame() {
        SandboxStore store = new SandboxStore();
        McguessDocumentGameRepository repo = new McguessDocumentGameRepository(store);
        McguessGame older = new McguessGame("g1", "conn-1", "qq", "20001", "diamond_sword", "10001", "1", 1000L);
        older.win("10001", "1", 1500L);
        McguessGame newer = new McguessGame("g2", "conn-1", "qq", "20001", "iron_pickaxe", "10002", null, 2000L);
        repo.save(older);
        repo.save(newer);

        assertEquals("g2", repo.findLatest("conn-1", "20001").orElseThrow().getId());
        assertEquals("g2", repo.findActive("conn-1", "20001").orElseThrow().getId());

        newer.lose(2500L);
        repo.save(newer);
        assertTrue(repo.findActive("conn-1", "20001").isEmpty(), "终局后不再有进行中对局");
        assertEquals("g2", repo.findLatest("conn-1", "20001").orElseThrow().getId(), "终局仍可查到最近一局");
    }

    @Test
    void legacyItemGameDocumentReadsWithDefaults() {
        // 1.0.0 旧文档：date 字段、无 mode/startedBy*/channelKey/emptyStreak 等新字段
        SandboxStore store = new SandboxStore();
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("id", "legacy-1");
        legacy.put("date", "2026-08-01");
        legacy.put("connectionId", "conn-1");
        legacy.put("channelId", "20001");
        legacy.put("targetId", "diamond_sword");
        legacy.put("status", McguessGame.STATUS_WON);
        legacy.put("winnerQq", "10001");
        legacy.put("startedAt", 1000L);
        legacy.put("endedAt", 1500L);
        store.save("mcguess_games", "legacy-1", legacy);

        McguessDocumentGameRepository repo = new McguessDocumentGameRepository(store);
        McguessGame game = repo.findById("legacy-1").orElseThrow();
        assertEquals(McguessGame.STATUS_WON, game.getStatus());
        assertEquals(0, game.getEmptyStreak());
        assertEquals(0, game.getHintsUsed());
        assertNull(game.getStartedByUserId());
    }

    // ---------------------------------------------------------------- 猜生物

    @Test
    void mobGameCellsAndHeartsSurviveSandboxStore() {
        SandboxStore store = new SandboxStore();
        McguessDocumentMobGameRepository repo = new McguessDocumentMobGameRepository(store);
        MobGame game = new MobGame("m1", "conn-1", "qq", "20001",
                List.of("hostile", "undead", "overworld"), List.of("hostile", "undead", "overworld"),
                List.of("zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie", "zombie"),
                null, null, 1000L);
        game.fill(1, "zombie");
        game.loseHeart();
        game.addGuess(new MobGuess(2, "苦力怕", "creeper", "苦力怕", MobGuess.RESULT_WRONG, "10001", null, 1100L));

        repo.save(game);

        MobGame reloaded = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals("zombie", reloaded.getCells().get(0));
        assertNull(reloaded.getCells().get(1), "空格序列化为空串后应读回 null");
        assertEquals(9, reloaded.getCells().size());
        assertEquals(MobGame.MAX_HEARTS - 1, reloaded.getHearts());
        assertEquals(1, reloaded.getGuesses().size());
        assertEquals(MobGuess.RESULT_WRONG, reloaded.getGuesses().get(0).result());
    }

    @Test
    void mobGameShortCellListIsPadded() {
        // 异常/旧格式文档：cells 不足 9 格时读回补齐
        SandboxStore store = new SandboxStore();
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", "m-legacy");
        doc.put("connectionId", "conn-1");
        doc.put("channelId", "20001");
        doc.put("channelKey", "conn-1:20001");
        doc.put("rowConds", List.of("hostile", "undead", "overworld"));
        doc.put("colConds", List.of("hostile", "undead", "overworld"));
        doc.put("solution", List.of("zombie"));
        doc.put("cells", List.of("zombie", ""));
        doc.put("status", MobGame.STATUS_PLAYING);
        doc.put("startedAt", 1000L);
        store.save("mcguess_mob_games", "m-legacy", doc);

        McguessDocumentMobGameRepository repo = new McguessDocumentMobGameRepository(store);
        MobGame game = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals(9, game.getCells().size());
        assertEquals(1, game.filledCount());
        assertEquals(MobGame.MAX_HEARTS, game.getHearts(), "缺 hearts 字段按初始心数读取");
    }

    // ---------------------------------------------------------------- 猜合成

    @Test
    void recipeGameGridAndCountersSurviveSandboxStore() {
        SandboxStore store = new SandboxStore();
        McguessDocumentRecipeGameRepository repo = new McguessDocumentRecipeGameRepository(store);
        RecipeGame game = new RecipeGame("r1", "conn-1", "qq", "20001", "iron_pickaxe",
                Arrays.asList("iron_ingot", "iron_ingot", "iron_ingot", null, "stick", null, null, "stick", null),
                "10001", "1", 1000L);
        game.revealItem("iron_ingot");
        game.restoreCounters(3, 1);
        game.addGuess(new RecipeGuess(4, "石头", null, null, RecipeGuess.RESULT_EMPTY, "10001", "1", 1100L));

        repo.save(game);

        RecipeGame reloaded = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals("iron_ingot", reloaded.getGrid().get(0));
        assertNull(reloaded.getGrid().get(3), "空位序列化为空串后应读回 null");
        assertEquals(9, reloaded.getGrid().size());
        assertTrue(reloaded.getRevealed().contains("iron_ingot"));
        assertEquals(3, reloaded.getEmptyStreak());
        assertEquals(1, reloaded.getHintsUsed());
        assertEquals(1, reloaded.getGuesses().size());
        assertNull(reloaded.getGuesses().get(0).matchedId());
    }

    // ---------------------------------------------------------------- 玩家战绩

    @Test
    void playerWithNullFieldsSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        McguessDocumentPlayerRepository repo = new McguessDocumentPlayerRepository(store);
        McguessPlayer player = new McguessPlayer("42");
        player.recordPlayed(McguessMode.ITEM, 1000L);
        player.recordWin(McguessMode.ITEM, 1000L);
        player.recordPlayed(McguessMode.MOB, 2000L);
        player.recordGuess(3000L);

        repo.save(player);

        McguessPlayer reloaded = repo.findByUserId("42").orElseThrow();
        assertNull(reloaded.getQq());
        assertNull(reloaded.getNickname());
        assertEquals(1, reloaded.getItemPlayed());
        assertEquals(1, reloaded.getItemWins());
        assertEquals(1, reloaded.getMobPlayed());
        assertEquals(0, reloaded.getMobWins());
        assertEquals(1, reloaded.getTotalGuesses());
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
