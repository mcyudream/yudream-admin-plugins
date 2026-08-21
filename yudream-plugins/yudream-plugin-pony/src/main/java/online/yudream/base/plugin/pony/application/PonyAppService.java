package online.yudream.base.plugin.pony.application;

import online.yudream.base.plugin.pony.application.dto.Paged;
import online.yudream.base.plugin.pony.application.dto.PonyGameView;
import online.yudream.base.plugin.pony.application.dto.PonyOverview;
import online.yudream.base.plugin.pony.application.dto.PonyPlayerView;
import online.yudream.base.plugin.pony.domain.HorsePlacement;
import online.yudream.base.plugin.pony.domain.PonyGame;
import online.yudream.base.plugin.pony.domain.PonyGameRepository;
import online.yudream.base.plugin.pony.domain.PonyGenerator;
import online.yudream.base.plugin.pony.domain.PonyPlayer;
import online.yudream.base.plugin.pony.domain.PonyPlayerRepository;
import online.yudream.base.plugin.pony.domain.PonyPuzzle;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PonyAppService {

    public static final int MIN_SIZE = 6;
    public static final int MAX_SIZE = 9;

    /** 文本降级渲染时的色块调色板（最多 9 色，对应 6-9 尺寸）。 */
    private static final String[] REGION_TILES = {"🟨", "🟥", "🟦", "🟩", "🟪", "🟧", "🟫", "⬛", "⬜"};

    private final PonyGameRepository games;
    private final PonyPlayerRepository players;
    private final FrameworkServices framework;
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    public PonyAppService(PonyGameRepository games, PonyPlayerRepository players, FrameworkServices framework) {
        this.games = games;
        this.players = players;
        this.framework = framework;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String startGame(PluginEvent event, Long userId, Integer sizeArg) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中开始小马归位。";
        }
        int size = sizeArg == null ? 8 : sizeArg;
        if (size < MIN_SIZE || size > MAX_SIZE) {
            return "棋盘尺寸需要在 " + MIN_SIZE + "-" + MAX_SIZE + " 之间。";
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> existing = games.findActive(event.connectionId(), event.channelId());
            if (existing.isPresent()) {
                PonyGame game = existing.get();
                return "本群已有一局进行中的对局（" + describe(game) + "，已放 " + game.getHorses().size()
                        + "/" + game.getSize() + " 匹）。发送 /小马状态 查看棋盘，或 /结束小马 投降揭晓。";
            }
            PonyPuzzle puzzle = PonyGenerator.generate(size);
            PonyGame game = new PonyGame(UUID.randomUUID().toString(), event.connectionId(), event.platform(),
                    event.channelId(), puzzle, event.userId(), userIdString(userId), System.currentTimeMillis());
            games.save(game);
            return "🐴 小马归位开局！" + describe(game)
                    + "\n规则：每行、每列、每种颜色各 1 匹小马，任意两匹小马不能相邻（含斜角）。"
                    + "\n发送 /马 <列> <行> 放马（如 /马 4 5），/标 <列> <行> 打叉排除，/小马状态 查看棋盘，/结束小马 投降揭晓。";
        }
    }

    public String toggleMark(PluginEvent event, Integer colArg, Integer rowArg) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局，发送 /小马 开始一局。";
            }
            PonyGame game = found.get();
            String error = checkCoordinates(game, colArg, rowArg);
            if (error != null) {
                return error;
            }
            int row = rowArg - 1;
            int col = colArg - 1;
            if (game.horseAt(game.index(row, col))) {
                return "（" + colArg + "," + rowArg + "）已经有小马了，不用标记。";
            }
            boolean marked = game.toggleMark(row, col);
            games.save(game);
            return marked
                    ? "已在（" + colArg + "," + rowArg + "）标记 ×。"
                    : "已取消（" + colArg + "," + rowArg + "）的 × 标记。";
        }
    }

    public String placeHorse(PluginEvent event, Long userId, Integer colArg, Integer rowArg) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中放马。";
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局，发送 /小马 开始一局。";
            }
            PonyGame game = found.get();
            String error = checkCoordinates(game, colArg, rowArg);
            if (error != null) {
                return error;
            }
            int row = rowArg - 1;
            int col = colArg - 1;
            int index = game.index(row, col);
            if (game.horseAt(index)) {
                return "（" + colArg + "," + rowArg + "）已经放过小马了。";
            }
            if (game.getMarks().contains(index)) {
                return "（" + colArg + "," + rowArg + "）被标记为 ×，先发送 /标 " + colArg + " " + rowArg + " 取消标记再放马。";
            }
            if (!game.isSolutionCell(row, col)) {
                game.miss();
                StringBuilder reply = new StringBuilder("❌ （").append(colArg).append(",").append(rowArg)
                        .append("）不能放小马，扣除 1 点生命（").append(hearts(game)).append("）。");
                if (game.getLives() <= 0) {
                    game.lose(System.currentTimeMillis());
                    games.save(game);
                    recordStats(game, false);
                    reply.append("\n💔 生命耗尽，本局失败！正确答案已标注在棋盘上，发送 /小马 再来一局。");
                    return reply.toString();
                }
                games.save(game);
                return reply.toString();
            }
            game.placeHorse(row, col, event.userId(), userIdString(userId), System.currentTimeMillis());
            if (game.remaining() <= 0) {
                game.win(event.userId(), userIdString(userId), System.currentTimeMillis());
                games.save(game);
                recordStats(game, true);
                return "🎉 QQ " + event.userId() + " 在（" + colArg + "," + rowArg
                        + "）放下最后一匹小马，全部归位！本局共用 "
                        + ((game.getEndedAt() - game.getStartedAt()) / 1000) + " 秒，失误 " + game.getMistakes() + " 次。";
            }
            games.save(game);
            return "✅ QQ " + event.userId() + " 在（" + colArg + "," + rowArg
                    + "）放对了小马！该行、列、周围一圈与同色区域已自动标记 ×，还剩 " + game.remaining() + " 匹。";
        }
    }

    public String gameStatus(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局，发送 /小马 开始一局。";
            }
            PonyGame game = found.get();
            return "🐴 进行中的对局：" + describe(game)
                    + "\n生命 " + hearts(game) + " · 失误 " + game.getMistakes() + " 次"
                    + "\n发送 /马 <列> <行> 放马，/标 <列> <行> 打叉排除。";
        }
    }

    public String endGame(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局。";
            }
            PonyGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            recordStats(game, false);
            return "🏳️ 对局已结束，正确答案已标注在棋盘上。发送 /小马 再来一局。";
        }
    }

    public String myStats(Long userId) {
        if (userId == null) {
            return "请先使用 /绑定 绑定系统账号后再查看战绩。";
        }
        Optional<PonyPlayer> found = players.findByUserId(String.valueOf(userId));
        if (found.isEmpty()) {
            return "你还没有小马战绩，去群里发送 /小马 开局吧！";
        }
        PonyPlayer player = found.get();
        return "📊 你的小马归位战绩"
                + "\n对局 " + player.getPlayed() + " · 胜 " + player.getWins()
                + "（胜率 " + winRate(player.getPlayed(), player.getWins()) + "）"
                + "\n累计放对 " + player.getHorsesPlaced() + " 匹小马"
                + "\n当前连胜 " + player.getCurrentStreak() + " · 最高连胜 " + player.getBestStreak();
    }

    public String leaderboard() {
        List<PonyPlayer> top = players.search(1, 10);
        if (top.isEmpty()) {
            return "还没有玩家战绩，发送 /小马 开打第一局！";
        }
        StringBuilder reply = new StringBuilder("🏆 小马归位排行榜（按胜场）");
        int rank = 1;
        for (PonyPlayer player : top) {
            if (player.getPlayed() <= 0) {
                continue;
            }
            reply.append("\n").append(rank++).append(". ").append(displayName(player))
                    .append("  胜 ").append(player.getWins()).append(" / 玩 ").append(player.getPlayed())
                    .append("（胜率 ").append(winRate(player.getPlayed(), player.getWins())).append("）");
        }
        return reply.toString();
    }

    // ---------------------------------------------------------------- 棋盘渲染

    /**
     * 群内最近一局对局的棋盘渲染变量快照；本群从未开局时返回 null。
     * 在渠道锁内构建，避免模板异步渲染期间对局被并发修改。
     * rows 按展示顺序（顶部第 N 行 → 底部第 1 行）输出；对局结束时揭晓全部答案小马。
     */
    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findLatest(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return null;
            }
            PonyGame game = found.get();
            boolean finished = !game.isPlaying();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int row = game.getSize() - 1; row >= 0; row--) {
                List<Map<String, Object>> cells = new ArrayList<>();
                for (int col = 0; col < game.getSize(); col++) {
                    int index = game.index(row, col);
                    boolean horse = game.horseAt(index);
                    boolean reveal = finished && !horse && game.isSolutionCell(row, col);
                    boolean x = !horse && !reveal && game.getMarks().contains(index);
                    StringBuilder cls = new StringBuilder("region-").append(game.getRegions().get(index));
                    if (x) {
                        cls.append(" x");
                    }
                    if (reveal) {
                        cls.append(" reveal");
                    }
                    cells.add(Map.of(
                            "ch", horse || reveal ? "🐴" : (x ? "✕" : ""),
                            "cls", cls.toString()));
                }
                rows.add(Map.of("label", String.valueOf(row + 1), "cells", cells));
            }
            List<String> colLabels = new ArrayList<>();
            for (int col = 1; col <= game.getSize(); col++) {
                colLabels.add(String.valueOf(col));
            }
            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "小马归位");
            variables.put("subtitle", game.getSize() + "×" + game.getSize() + " · 剩余 " + game.remaining() + " 匹");
            variables.put("hearts", hearts(game));
            variables.put("statusText", statusText(game));
            variables.put("finished", finished);
            variables.put("won", PonyGame.STATUS_WON.equals(game.getStatus()));
            variables.put("rows", rows);
            variables.put("colLabels", colLabels);
            variables.put("banner", banner);
            return variables;
        }
    }

    /**
     * 文本降级棋盘：色块用 emoji 区分，× 为已排除格，🐴 为已放（或揭晓）的小马。
     */
    public String renderBoardText(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (lockFor(event)) {
            Optional<PonyGame> found = games.findLatest(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return null;
            }
            PonyGame game = found.get();
            boolean finished = !game.isPlaying();
            StringBuilder board = new StringBuilder();
            for (int row = game.getSize() - 1; row >= 0; row--) {
                board.append(row + 1).append(' ');
                for (int col = 0; col < game.getSize(); col++) {
                    int index = game.index(row, col);
                    if (game.horseAt(index) || (finished && game.isSolutionCell(row, col))) {
                        board.append("🐴");
                    } else if (game.getMarks().contains(index)) {
                        board.append("❌");
                    } else {
                        board.append(REGION_TILES[game.getRegions().get(index) % REGION_TILES.length]);
                    }
                }
                board.append('\n');
            }
            board.append("  ");
            for (int col = 1; col <= game.getSize(); col++) {
                board.append(col).append(col < game.getSize() ? " " : "");
            }
            return board.toString();
        }
    }

    // ---------------------------------------------------------------- 用户端 HTTP

    public PonyPlayerView myStatsView(String userId) {
        return players.findByUserId(userId).map(this::toPlayerView).orElse(null);
    }

    // ---------------------------------------------------------------- 管理端 HTTP

    public PonyOverview overview() {
        return new PonyOverview(games.countAll(), games.count(PonyGame.STATUS_PLAYING),
                games.count(PonyGame.STATUS_WON), players.count());
    }

    public Paged<PonyGameView> searchGames(String status, int page, int size) {
        List<PonyGameView> records = games.search(status, page, size).stream().map(this::toGameView).toList();
        return new Paged<>(records, games.count(status));
    }

    public Paged<PonyPlayerView> searchPlayers(int page, int size) {
        List<PonyPlayerView> records = players.search(page, size).stream().map(this::toPlayerView).toList();
        return new Paged<>(records, players.count());
    }

    // ---------------------------------------------------------------- 内部支撑

    private String checkCoordinates(PonyGame game, Integer col, Integer row) {
        if (col == null || row == null) {
            return "坐标格式：/马 <列> <行> 或 /标 <列> <行>，列与行都是 1-" + game.getSize() + " 的数字，例如 /马 4 5。";
        }
        if (col < 1 || col > game.getSize() || row < 1 || row > game.getSize()) {
            return "坐标超出棋盘范围（1-" + game.getSize() + "）。";
        }
        return null;
    }

    private void recordStats(PonyGame game, boolean won) {
        Map<String, Integer> placedByUser = new HashMap<>();
        for (HorsePlacement horse : game.getHorses()) {
            if (horse.userId() != null && !horse.userId().isBlank()) {
                placedByUser.merge(horse.userId(), 1, Integer::sum);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> entry : placedByUser.entrySet()) {
            String participantId = entry.getKey();
            PonyPlayer player = players.findByUserId(participantId).orElseGet(() -> new PonyPlayer(participantId));
            player.setQq(game.lastQqOf(participantId));
            resolveNickname(player);
            player.recordPlayed(now);
            if (won) {
                player.recordWin(entry.getValue(), now);
            } else {
                player.recordLoss(entry.getValue(), now);
            }
            players.save(player);
        }
    }

    private void resolveNickname(PonyPlayer player) {
        try {
            framework.users().findById(Long.valueOf(player.getUserId())).ifPresent(profile -> {
                String nickname = profile.nickname() == null || profile.nickname().isBlank() ? profile.username() : profile.nickname();
                player.setNickname(nickname);
            });
        } catch (RuntimeException ignored) {
            // 昵称解析失败不影响战绩记录
        }
    }

    private String displayName(PonyPlayer player) {
        if (player.getNickname() != null && !player.getNickname().isBlank()) {
            return player.getNickname();
        }
        return "QQ " + (player.getQq() == null ? player.getUserId() : player.getQq());
    }

    private String describe(PonyGame game) {
        return game.getSize() + "×" + game.getSize() + " 棋盘 · 共 " + game.getSize() + " 匹小马";
    }

    private String hearts(PonyGame game) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < PonyGame.MAX_LIVES; i++) {
            builder.append(i < game.getLives() ? "❤️" : "🖤");
        }
        return builder.toString();
    }

    private String statusText(PonyGame game) {
        return switch (game.getStatus()) {
            case PonyGame.STATUS_WON -> "已全部归位";
            case PonyGame.STATUS_LOST -> "已结束";
            default -> "进行中";
        };
    }

    private String winRate(int played, int wins) {
        return played <= 0 ? "0%" : Math.round(wins * 100.0 / played) + "%";
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }

    private Object lockFor(PluginEvent event) {
        return channelLocks.computeIfAbsent(PonyGame.channelKey(event.connectionId(), event.channelId()), key -> new Object());
    }

    private PonyGameView toGameView(PonyGame game) {
        return new PonyGameView(game.getId(), game.getChannelId(), game.getSize(), game.getStatus(),
                game.getHorses().size(), game.getLives(), game.getMistakes(),
                game.getStartedByQq(), game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private PonyPlayerView toPlayerView(PonyPlayer player) {
        return new PonyPlayerView(player.getUserId(), player.getQq(), player.getNickname(),
                player.getPlayed(), player.getWins(), player.getHorsesPlaced(),
                player.getCurrentStreak(), player.getBestStreak(), player.getUpdatedAt());
    }
}
