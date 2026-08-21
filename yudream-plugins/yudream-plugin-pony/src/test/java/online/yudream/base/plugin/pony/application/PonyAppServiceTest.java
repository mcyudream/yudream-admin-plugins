package online.yudream.base.plugin.pony.application;

import online.yudream.base.plugin.pony.domain.HorsePlacement;
import online.yudream.base.plugin.pony.domain.PonyGame;
import online.yudream.base.plugin.pony.domain.PonyGameRepository;
import online.yudream.base.plugin.pony.domain.PonyPlayer;
import online.yudream.base.plugin.pony.domain.PonyPlayerRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonyAppServiceTest {

    private InMemoryGames games;
    private InMemoryPlayers players;
    private PonyAppService appService;

    @BeforeEach
    void setUp() {
        games = new InMemoryGames();
        players = new InMemoryPlayers();
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(
                PonyAppServiceTest.class.getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> null);
        appService = new PonyAppService(games, players, framework);
    }

    @Test
    void startsGameWithDefaultSize() {
        String reply = appService.startGame(event("10001"), 100L, null);
        assertTrue(reply.contains("小马归位开局"));
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        assertEquals(8, game.getSize());
        assertEquals(PonyGame.MAX_LIVES, game.getLives());
        String second = appService.startGame(event("10002"), 101L, 6);
        assertTrue(second.contains("已有一局进行中的对局"));
    }

    @Test
    void rejectsInvalidSizeAndCoordinates() {
        assertTrue(appService.startGame(event("10001"), 100L, 3).contains("尺寸"));
        appService.startGame(event("10001"), 100L, null);
        assertTrue(appService.placeHorse(event("10001"), 100L, null, 5).contains("坐标格式"));
        assertTrue(appService.placeHorse(event("10001"), 100L, 9, 5).contains("超出棋盘范围"));
        assertTrue(appService.toggleMark(event("10001"), 0, 5).contains("超出棋盘范围"));
    }

    @Test
    void correctPlacementAutoMarksRingRowColumnAndRegion() {
        appService.startGame(event("10001"), 100L, 6);
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        int row = 0;
        int col = game.getSolution().get(row);
        String reply = appService.placeHorse(event("10001"), 100L, col + 1, row + 1);
        assertTrue(reply.contains("放对了小马"));
        assertEquals(1, game.getHorses().size());
        assertEquals(game.getSize() - 1, game.remaining());
        int region = game.getRegions().get(game.index(row, col));
        for (int r = 0; r < game.getSize(); r++) {
            for (int c = 0; c < game.getSize(); c++) {
                int index = game.index(r, c);
                boolean ring = Math.abs(r - row) <= 1 && Math.abs(c - col) <= 1;
                boolean shouldMark = (r == row || c == col || ring || game.getRegions().get(index) == region)
                        && !(r == row && c == col);
                assertEquals(shouldMark, game.getMarks().contains(index),
                        "格 (" + c + "," + r + ") 标记状态不符");
            }
        }
        // 已放马的格子不可再标记或重复放马
        assertTrue(appService.toggleMark(event("10001"), col + 1, row + 1).contains("不用标记"));
        assertTrue(appService.placeHorse(event("10001"), 100L, col + 1, row + 1).contains("已经放过"));
    }

    @Test
    void wrongPlacementCostsLifeAndExhaustionLoses() {
        appService.startGame(event("10001"), 100L, 6);
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        int row = 0;
        int wrongCol = (game.getSolution().get(row) + 1) % game.getSize();
        String reply = appService.placeHorse(event("10001"), 100L, wrongCol + 1, row + 1);
        assertTrue(reply.contains("扣除 1 点生命"));
        assertEquals(PonyGame.MAX_LIVES - 1, game.getLives());
        assertTrue(game.isPlaying());
        appService.placeHorse(event("10001"), 100L, wrongCol + 1, row + 1);
        String last = appService.placeHorse(event("10001"), 100L, wrongCol + 1, row + 1);
        assertTrue(last.contains("生命耗尽"));
        assertEquals(PonyGame.STATUS_LOST, game.getStatus());
        // 失败后棋盘揭晓答案
        Map<String, Object> vars = appService.boardVariables(event("10001"), null);
        assertEquals(true, vars.get("finished"));
        assertEquals(false, vars.get("won"));
    }

    @Test
    void markedCellCannotTakeHorseUntilUnmarked() {
        appService.startGame(event("10001"), 100L, 6);
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        int row = 0;
        int col = game.getSolution().get(row);
        assertTrue(appService.toggleMark(event("10001"), col + 1, row + 1).contains("标记 ×"));
        assertTrue(appService.placeHorse(event("10001"), 100L, col + 1, row + 1).contains("取消标记"));
        assertTrue(appService.toggleMark(event("10001"), col + 1, row + 1).contains("取消"));
        assertTrue(appService.placeHorse(event("10001"), 100L, col + 1, row + 1).contains("放对了小马"));
    }

    @Test
    void fullWinFlowRecordsStatsForAllPlacers() {
        appService.startGame(event("10001"), 100L, 6);
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        String last = null;
        for (int row = 0; row < game.getSize(); row++) {
            int col = game.getSolution().get(row);
            String qq = row < 3 ? "10001" : "10002";
            Long userId = row < 3 ? 100L : 200L;
            last = appService.placeHorse(event(qq), userId, col + 1, row + 1);
        }
        assertNotNull(last);
        assertTrue(last.contains("全部归位"));
        assertEquals(PonyGame.STATUS_WON, game.getStatus());
        PonyPlayer a = players.findByUserId("100").orElseThrow();
        assertEquals(1, a.getPlayed());
        assertEquals(1, a.getWins());
        assertEquals(3, a.getHorsesPlaced());
        assertEquals(1, a.getCurrentStreak());
        PonyPlayer b = players.findByUserId("200").orElseThrow();
        assertEquals(1, b.getWins());
        assertEquals(3, b.getHorsesPlaced());
    }

    @Test
    void endGameRevealsAndResetsStreak() {
        appService.startGame(event("10001"), 100L, 6);
        PonyGame game = games.findActive("conn-1", "8888").orElseThrow();
        appService.placeHorse(event("10001"), 100L, game.getSolution().get(0) + 1, 1);
        String reply = appService.endGame(event("10001"));
        assertTrue(reply.contains("对局已结束"));
        assertEquals(PonyGame.STATUS_LOST, game.getStatus());
        PonyPlayer player = players.findByUserId("100").orElseThrow();
        assertEquals(1, player.getPlayed());
        assertEquals(0, player.getWins());
        assertEquals(0, player.getCurrentStreak());
        assertTrue(appService.endGame(event("10001")).contains("没有进行中的对局"));
    }

    @Test
    void boardVariablesStructure() {
        assertNull(appService.boardVariables(event("10001"), "x"));
        appService.startGame(event("10001"), 100L, null);
        Map<String, Object> vars = appService.boardVariables(event("10001"), "横幅");
        assertEquals("小马归位", vars.get("title"));
        assertEquals("进行中", vars.get("statusText"));
        assertEquals("横幅", vars.get("banner"));
        assertEquals(false, vars.get("finished"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) vars.get("rows");
        assertEquals(8, rows.size());
        // 顶部第一行展示的是第 8 行
        assertEquals("8", rows.get(0).get("label"));
        assertEquals("1", rows.get(7).get("label"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) rows.get(0).get("cells");
        assertEquals(8, cells.size());
        for (Map<String, Object> cell : cells) {
            assertTrue(String.valueOf(cell.get("cls")).startsWith("region-"));
            assertEquals("", cell.get("ch"));
        }
        @SuppressWarnings("unchecked")
        List<String> colLabels = (List<String>) vars.get("colLabels");
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8"), colLabels);
        // 文本降级棋盘包含坐标与色块
        String text = appService.renderBoardText(event("10001"));
        assertNotNull(text);
        assertTrue(text.contains("8 "));
    }

    @Test
    void myStatsRequiresBinding() {
        assertTrue(appService.myStats(null).contains("绑定"));
        assertTrue(appService.myStats(999L).contains("还没有小马战绩"));
    }

    @Test
    void leaderboardRanksByWins() {
        PonyPlayer a = new PonyPlayer("1");
        a.setQq("10001");
        a.recordPlayed(0);
        a.recordPlayed(0);
        a.recordWin(5, 0);
        players.save(a);
        PonyPlayer b = new PonyPlayer("2");
        b.setQq("10002");
        b.recordPlayed(0);
        players.save(b);
        String reply = appService.leaderboard();
        assertTrue(reply.indexOf("10001") < reply.indexOf("10002"));
    }

    private PluginEvent event(String qq) {
        return new PluginEvent("seq", "message_receive", "milky", qq, "8888", "", null, null,
                Map.of(), null, null, "conn-1", "self-1", "msg-1");
    }

    static class InMemoryGames implements PonyGameRepository {
        final Map<String, PonyGame> data = new LinkedHashMap<>();

        @Override
        public Optional<PonyGame> findActive(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.isPlaying() && game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .findFirst();
        }

        @Override
        public Optional<PonyGame> findLatest(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .max(Comparator.comparingLong(PonyGame::getStartedAt));
        }

        @Override
        public List<PonyGame> search(String status, int page, int size) {
            List<PonyGame> all = data.values().stream()
                    .filter(game -> status == null || game.getStatus().equals(status))
                    .sorted(Comparator.comparingLong(PonyGame::getStartedAt).reversed())
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
        public void save(PonyGame game) {
            data.put(game.getId(), game);
        }

        @Override
        public void delete(String id) {
            data.remove(id);
        }
    }

    static class InMemoryPlayers implements PonyPlayerRepository {
        final Map<String, PonyPlayer> data = new LinkedHashMap<>();

        @Override
        public Optional<PonyPlayer> findByUserId(String userId) {
            return Optional.ofNullable(data.get(userId));
        }

        @Override
        public List<PonyPlayer> findAll() {
            return new ArrayList<>(data.values());
        }

        @Override
        public List<PonyPlayer> search(int page, int size) {
            List<PonyPlayer> all = findAll();
            all.sort(Comparator.comparingInt(PonyPlayer::getWins).reversed());
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count() {
            return data.size();
        }

        @Override
        public void save(PonyPlayer player) {
            data.put(player.getUserId(), player);
        }
    }
}
