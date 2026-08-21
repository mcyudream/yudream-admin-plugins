package online.yudream.base.plugin.wordle.application;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.wordle.domain.LetterState;
import online.yudream.base.plugin.wordle.domain.WordEntry;
import online.yudream.base.plugin.wordle.domain.WordEntryRepository;
import online.yudream.base.plugin.wordle.domain.WordleEvaluator;
import online.yudream.base.plugin.wordle.domain.WordleGame;
import online.yudream.base.plugin.wordle.domain.WordleGameRepository;
import online.yudream.base.plugin.wordle.domain.WordleMode;
import online.yudream.base.plugin.wordle.domain.WordlePlayer;
import online.yudream.base.plugin.wordle.domain.WordlePlayerRepository;
import online.yudream.base.plugin.wordle.infrastructure.PinyinDictionary;
import online.yudream.base.plugin.wordle.infrastructure.WordBank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordleAppServiceTest {

    private InMemoryGames games;
    private InMemoryPlayers players;
    private InMemoryWords words;
    private WordleAppService appService;

    @BeforeEach
    void setUp() {
        games = new InMemoryGames();
        players = new InMemoryPlayers();
        words = new InMemoryWords();
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(
                WordleAppServiceTest.class.getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> null);
        appService = new WordleAppService(games, players, words, new WordBank(words), new PinyinDictionary(), framework);
    }

    @Test
    void startsEnglishGameWithDefaultLength() {
        String reply = appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        assertTrue(reply.contains("猜词游戏开始"));
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        assertEquals(WordleMode.ENGLISH, game.getMode());
        assertEquals(5, game.length());
        assertEquals(6 + 5 - 1, game.getMaxGuesses());
    }

    @Test
    void rejectsSecondGameInSameChannel() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        String reply = appService.startGame(event("10002"), 101L, WordleMode.IDIOM, 4, false);
        assertTrue(reply.contains("已有一局进行中的对局"));
    }

    @Test
    void fullWinFlowRecordsStats() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String reply = appService.guess(event("10002"), 200L, game.getAnswer());
        assertTrue(reply.contains("猜中答案"));
        assertFalse(game.isPlaying());
        WordlePlayer winner = players.findByUserId("200").orElseThrow();
        assertEquals(1, winner.getEnglishPlayed());
        assertEquals(1, winner.getEnglishWins());
        assertEquals(1, winner.getCurrentStreak());
        assertEquals(1, winner.getBestStreak());
        assertEquals(1, winner.getWinDistribution().get("1"));
        // 开局但未猜的绑定用户不计入战绩
        assertTrue(players.findByUserId("100").isEmpty());
    }

    @Test
    void duplicateGuessIsRejected() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String probe = game.getAnswer().equals("apple") ? "brick" : "apple";
        appService.guess(event("10001"), 100L, probe);
        String reply = appService.guess(event("10001"), 100L, probe);
        assertTrue(reply.contains("已经猜过"));
        assertEquals(1, game.getGuesses().size());
    }

    @Test
    void invalidGuessIsRejectedWithoutConsuming() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String reply = appService.guess(event("10001"), 100L, "ab1");
        assertTrue(reply.contains("5 个英文字母"));
        assertTrue(game.getGuesses().isEmpty());
    }

    @Test
    void guessWithoutGameHintsStart() {
        String reply = appService.guess(event("10001"), 100L, "apple");
        assertTrue(reply.contains("没有进行中的对局"));
    }

    @Test
    void endGameRevealsAnswerAndResetsStreak() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String probe = game.getAnswer().equals("apple") ? "brick" : "apple";
        appService.guess(event("10001"), 100L, probe);
        String reply = appService.endGame(event("10001"));
        assertTrue(reply.contains(game.getAnswer()));
        assertEquals(WordleGame.STATUS_LOST, game.getStatus());
        WordlePlayer player = players.findByUserId("100").orElseThrow();
        assertEquals(1, player.getEnglishPlayed());
        assertEquals(0, player.getEnglishWins());
        assertEquals(0, player.getCurrentStreak());
    }

    @Test
    void exhaustionLosesGame() {
        appService.startGame(event("10001"), 100L, WordleMode.IDIOM, 4, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        List<String> pool = List.of("画蛇添足", "守株待兔", "亡羊补牢", "掩耳盗铃", "刻舟求剑", "井底之蛙",
                "杯弓蛇影", "狐假虎威", "对牛弹琴", "愚公移山", "精卫填海", "夸父逐日", "卧虎藏龙");
        String lastReply = null;
        for (String probe : pool) {
            if (probe.equals(game.getAnswer()) || game.hasGuessed(probe) || !game.isPlaying()) {
                continue;
            }
            lastReply = appService.guess(event("10001"), 100L, probe);
            if (!game.isPlaying()) {
                break;
            }
        }
        if (lastReply != null && game.getGuesses().size() >= game.getMaxGuesses()) {
            assertEquals(WordleGame.STATUS_LOST, game.getStatus());
        }
    }

    @Test
    void myStatsRequiresBinding() {
        assertTrue(appService.myStats(null).contains("绑定"));
        assertTrue(appService.myStats(999L).contains("还没有猜词战绩"));
    }

    @Test
    void leaderboardRanksByWins() {
        WordlePlayer a = new WordlePlayer("1");
        a.setQq("10001");
        a.recordPlayed(WordleMode.ENGLISH, 0);
        a.recordPlayed(WordleMode.ENGLISH, 0);
        a.recordWin(WordleMode.ENGLISH, 3, 0);
        players.save(a);
        WordlePlayer b = new WordlePlayer("2");
        b.setQq("10002");
        b.recordPlayed(WordleMode.IDIOM, 0);
        players.save(b);
        String reply = appService.leaderboard();
        assertTrue(reply.indexOf("10001") < reply.indexOf("10002"));
    }

    @Test
    void createWordValidatesAndDeduplicates() {
        var view = appService.createWord("ENGLISH", "Hello", "问候", "1");
        assertEquals("hello", view.word());
        assertEquals(5, view.length());
        assertThrows(IllegalArgumentException.class, () -> appService.createWord("ENGLISH", "hello", null, "1"));
        assertThrows(IllegalArgumentException.class, () -> appService.createWord("IDIOM", "太多字了啦", null, "1"));
        assertThrows(IllegalArgumentException.class, () -> appService.createWord("XX", "abc", null, "1"));
    }

    @Test
    void customWordJoinsAnswerPool() {
        appService.createWord("ENGLISH", "zztop", null, "1");
        WordBank bank = new WordBank(words);
        assertTrue(bank.answerPool(WordleMode.ENGLISH, 5).contains("zztop"));
        appService.updateWord("ENGLISH:zztop", null, false);
        assertFalse(new WordBank(words).answerPool(WordleMode.ENGLISH, 5).contains("zztop"));
    }

    @Test
    void boardVariablesIsNullWithoutGame() {
        assertNull(appService.boardVariables(event("10001"), "横幅"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void boardVariablesReflectsGameState() {
        appService.startGame(event("10001"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String probe = game.getAnswer().equals("apple") ? "brick" : "apple";
        appService.guess(event("10001"), 100L, probe);

        Map<String, Object> vars = appService.boardVariables(event("10001"), "横幅");
        assertEquals("猜单词", vars.get("title"));
        assertEquals(false, vars.get("finished"));
        assertEquals(false, vars.get("won"));
        assertEquals("进行中", vars.get("statusText"));
        assertEquals("横幅", vars.get("banner"));
        assertEquals(game.getAnswer().toUpperCase(java.util.Locale.ROOT), vars.get("answer"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) vars.get("rows");
        assertEquals(game.getMaxGuesses(), rows.size());
        List<Map<String, Object>> firstTiles = (List<Map<String, Object>>) rows.get(0).get("tiles");
        assertEquals(game.length(), firstTiles.size());
        List<LetterState> expected = WordleEvaluator.evaluate(game.getAnswer(), probe);
        Map<LetterState, String> classes = Map.of(
                LetterState.CORRECT, "revealed exact",
                LetterState.PRESENT, "revealed misplaced",
                LetterState.ABSENT, "revealed none");
        String[] probeChars = probe.codePoints()
                .mapToObj(codePoint -> new String(Character.toChars(Character.toUpperCase(codePoint))))
                .toArray(String[]::new);
        for (int i = 0; i < firstTiles.size(); i++) {
            assertEquals(classes.get(expected.get(i)), firstTiles.get(i).get("cls"));
            assertEquals(probeChars[i], firstTiles.get(i).get("ch"));
        }
        assertEquals(false, rows.get(0).get("solved"));
        for (int r = 1; r < rows.size(); r++) {
            List<Map<String, Object>> tiles = (List<Map<String, Object>>) rows.get(r).get("tiles");
            assertEquals(game.length(), tiles.size());
            assertEquals(false, rows.get(r).get("solved"));
            for (Map<String, Object> tile : tiles) {
                assertEquals("empty", tile.get("cls"));
                assertEquals("", tile.get("ch"));
            }
        }

        appService.guess(event("10002"), 200L, game.getAnswer());
        vars = appService.boardVariables(event("10001"), null);
        assertEquals(true, vars.get("finished"));
        assertEquals(true, vars.get("won"));
        assertEquals("已猜中", vars.get("statusText"));
        rows = (List<Map<String, Object>>) vars.get("rows");
        assertEquals(true, rows.get(1).get("solved"));
    }

    /**
     * 回归：宿主 SpringEL 读取 Map 缺失键会抛「Exception evaluating SpringEL expression」，
     * 因此每个 tile（含空行、英文模式）都必须始终包含 py 键。
     */
    @Test
    @SuppressWarnings("unchecked")
    void boardVariablesTilesAlwaysExposePinyinKey() {
        appService.startGame(event("10001"), 100L, WordleMode.IDIOM, 4, false);
        WordleGame game = games.findActive("conn-1", "8888").orElseThrow();
        String probe = game.getAnswer().equals("画蛇添足") ? "守株待兔" : "画蛇添足";
        appService.guess(event("10001"), 100L, probe);

        Map<String, Object> vars = appService.boardVariables(event("10001"), null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) vars.get("rows");
        assertEquals(game.getMaxGuesses(), rows.size());
        List<Map<String, Object>> guessedTiles = (List<Map<String, Object>>) rows.get(0).get("tiles");
        for (Map<String, Object> tile : guessedTiles) {
            assertTrue(tile.containsKey("py"), "已猜测格必须始终包含 py 键");
            Object py = tile.get("py");
            if (py != null) {
                Map<String, Object> pyMap = (Map<String, Object>) py;
                assertTrue(pyMap.containsKey("init") && pyMap.containsKey("initCls")
                        && pyMap.containsKey("finCls") && pyMap.containsKey("fin"), "拼音提示必须包含 init/initCls/fin/finCls 键");
                for (Map<String, Object> letter : (List<Map<String, Object>>) pyMap.get("fin")) {
                    assertTrue(letter.containsKey("ch") && letter.containsKey("tone") && letter.containsKey("toneCls"),
                            "韵母字母必须包含 ch/tone/toneCls 键");
                }
            }
        }
        for (int r = 1; r < rows.size(); r++) {
            for (Map<String, Object> tile : (List<Map<String, Object>>) rows.get(r).get("tiles")) {
                assertTrue(tile.containsKey("py"), "空行格必须始终包含 py 键");
                assertNull(tile.get("py"));
            }
        }

        appService.endGame(event("10001"));
        appService.startGame(event("10001", "9999"), 100L, WordleMode.ENGLISH, null, false);
        WordleGame english = games.findActive("conn-1", "9999").orElseThrow();
        String englishProbe = english.getAnswer().equals("apple") ? "brick" : "apple";
        appService.guess(event("10001", "9999"), 100L, englishProbe);
        rows = (List<Map<String, Object>>) appService.boardVariables(event("10001", "9999"), null).get("rows");
        for (Map<String, Object> row : rows) {
            for (Map<String, Object> tile : (List<Map<String, Object>>) row.get("tiles")) {
                assertTrue(tile.containsKey("py"), "英文模式格也必须始终包含 py 键");
                assertNull(tile.get("py"));
            }
        }
    }

    private PluginEvent event(String qq) {
        return event(qq, "8888");
    }

    private PluginEvent event(String qq, String channelId) {
        return new PluginEvent("seq", "message_receive", "milky", qq, channelId, "", null, null,
                Map.of(), null, null, "conn-1", "self-1", "msg-1");
    }

    static class InMemoryGames implements WordleGameRepository {
        final Map<String, WordleGame> data = new LinkedHashMap<>();

        @Override
        public Optional<WordleGame> findActive(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.isPlaying() && game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .findFirst();
        }

        @Override
        public Optional<WordleGame> findLatest(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .max(Comparator.comparingLong(WordleGame::getStartedAt));
        }

        @Override
        public List<WordleGame> search(String status, int page, int size) {
            List<WordleGame> all = data.values().stream()
                    .filter(game -> status == null || game.getStatus().equals(status))
                    .sorted(Comparator.comparingLong(WordleGame::getStartedAt).reversed())
                    .toList();
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count(String status) {
            return data.values().stream().filter(game -> game.getStatus().equals(status)).count();
        }

        @Override
        public long countAll() {
            return data.size();
        }

        @Override
        public void save(WordleGame game) {
            data.put(game.getId(), game);
        }

        @Override
        public void delete(String id) {
            data.remove(id);
        }
    }

    static class InMemoryPlayers implements WordlePlayerRepository {
        final Map<String, WordlePlayer> data = new LinkedHashMap<>();

        @Override
        public Optional<WordlePlayer> findByUserId(String userId) {
            return Optional.ofNullable(data.get(userId));
        }

        @Override
        public List<WordlePlayer> findAll() {
            return new ArrayList<>(data.values());
        }

        @Override
        public List<WordlePlayer> search(int page, int size) {
            List<WordlePlayer> all = findAll();
            all.sort(Comparator.comparingInt(WordlePlayer::totalWins).reversed());
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count() {
            return data.size();
        }

        @Override
        public void save(WordlePlayer player) {
            data.put(player.getUserId(), player);
        }
    }

    static class InMemoryWords implements WordEntryRepository {
        final Map<String, WordEntry> data = new LinkedHashMap<>();

        @Override
        public Optional<WordEntry> findById(String id) {
            return Optional.ofNullable(data.get(id));
        }

        @Override
        public List<WordEntry> search(String mode, String keyword, int page, int size) {
            List<WordEntry> all = data.values().stream()
                    .filter(entry -> mode == null || entry.getMode().name().equalsIgnoreCase(mode))
                    .filter(entry -> keyword == null || entry.getWord().contains(keyword))
                    .toList();
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count(String mode, String keyword) {
            return data.values().stream()
                    .filter(entry -> mode == null || entry.getMode().name().equalsIgnoreCase(mode))
                    .filter(entry -> keyword == null || entry.getWord().contains(keyword))
                    .count();
        }

        @Override
        public List<WordEntry> findEnabled(WordleMode mode) {
            return data.values().stream().filter(entry -> entry.getMode() == mode && entry.isEnabled()).toList();
        }

        @Override
        public long countAll() {
            return data.size();
        }

        @Override
        public void save(WordEntry entry) {
            data.put(entry.getId(), entry);
        }

        @Override
        public void delete(String id) {
            data.remove(id);
        }
    }
}
