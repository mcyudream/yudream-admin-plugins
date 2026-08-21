package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.application.dto.McguessGameView;
import online.yudream.base.plugin.mcguess.application.dto.McguessOverview;
import online.yudream.base.plugin.mcguess.application.dto.McguessPlayerView;
import online.yudream.base.plugin.mcguess.application.dto.Paged;
import online.yudream.base.plugin.mcguess.domain.BingoGame;
import online.yudream.base.plugin.mcguess.domain.BingoGameRepository;
import online.yudream.base.plugin.mcguess.domain.FogGame;
import online.yudream.base.plugin.mcguess.domain.FogGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McMobCatalog;
import online.yudream.base.plugin.mcguess.domain.McguessGame;
import online.yudream.base.plugin.mcguess.domain.McguessGameRepository;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.mcguess.domain.MobGame;
import online.yudream.base.plugin.mcguess.domain.MobGameRepository;
import online.yudream.base.plugin.mcguess.domain.QuizGame;
import online.yudream.base.plugin.mcguess.domain.QuizGameRepository;
import online.yudream.base.plugin.mcguess.domain.RecipeGame;
import online.yudream.base.plugin.mcguess.domain.RecipeGameRepository;
import online.yudream.base.plugin.mcguess.domain.SpotGame;
import online.yudream.base.plugin.mcguess.domain.SpotGameRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 战绩与统计用例：群聊战绩/排行指令、用户端「我的战绩」、管理端概览与对局/玩家分页。
 * 七种群模式（猜物 / 猜生物 / 猜合成 / 迷雾 / 快答 / 宾果 / 找茬）的统计在此汇总；
 * 比大小为个人玩法，只有玩家文档上的最佳连击，不产生群对局记录。
 */
public class McguessStatsService {

    /** 群聊排行榜展示条数。 */
    private static final int LEADERBOARD_LIMIT = 10;
    /** 合并多模式对局时的单库预取上限。 */
    private static final int MERGE_FETCH_LIMIT = 5000;

    private final McguessGameRepository itemGames;
    private final MobGameRepository mobGames;
    private final RecipeGameRepository recipeGames;
    private final FogGameRepository fogGames;
    private final QuizGameRepository quizGames;
    private final BingoGameRepository bingoGames;
    private final SpotGameRepository spotGames;
    private final McguessPlayerRepository players;
    private final McCatalog catalog;
    private final McMobCatalog mobCatalog;

    public McguessStatsService(McguessGameRepository itemGames, MobGameRepository mobGames,
                               RecipeGameRepository recipeGames, FogGameRepository fogGames,
                               QuizGameRepository quizGames, BingoGameRepository bingoGames,
                               SpotGameRepository spotGames, McguessPlayerRepository players,
                               McCatalog catalog, McMobCatalog mobCatalog) {
        this.itemGames = itemGames;
        this.mobGames = mobGames;
        this.recipeGames = recipeGames;
        this.fogGames = fogGames;
        this.quizGames = quizGames;
        this.bingoGames = bingoGames;
        this.spotGames = spotGames;
        this.players = players;
        this.catalog = catalog;
        this.mobCatalog = mobCatalog;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String myStats(Long userId) {
        if (userId == null) {
            return "查看战绩需要先绑定系统账号，发送 /绑定 完成绑定。";
        }
        return players.findByUserId(String.valueOf(userId))
                .map(player -> "📊 我的 MC 猜谜战绩"
                        + "\n猜物：参与 " + player.getItemPlayed() + " 局 · 获胜 " + player.getItemWins() + " 局"
                        + "\n猜生物：参与 " + player.getMobPlayed() + " 局 · 获胜 " + player.getMobWins() + " 局"
                        + "\n猜合成：参与 " + player.getRecipePlayed() + " 局 · 获胜 " + player.getRecipeWins() + " 局"
                        + "\n迷雾：参与 " + player.getFogPlayed() + " 局 · 获胜 " + player.getFogWins() + " 局"
                        + "\n快答：参与 " + player.getQuizPlayed() + " 局 · 获胜 " + player.getQuizWins() + " 局"
                        + "\n宾果：参与 " + player.getBingoPlayed() + " 局 · 获胜 " + player.getBingoWins() + " 局"
                        + "\n找茬：参与 " + player.getSpotPlayed() + " 局 · 获胜 " + player.getSpotWins() + " 局"
                        + "\n比大小：历史最佳连胜 " + player.getHolBest()
                        + "\n图鉴：已收集 " + player.collectionSize() + " 件"
                        + "\n累计有效猜测 " + player.getTotalGuesses() + " 次")
                .orElse("你还没有 MC 猜谜战绩，发送 /猜物、/猜生物、/猜合成、/迷雾、/快答、/宾果 或 /找茬 参与游戏吧！");
    }

    public String leaderboard() {
        List<McguessPlayer> top = players.search(1, LEADERBOARD_LIMIT);
        if (top.isEmpty()) {
            return "还没有玩家战绩，发送 /猜物、/猜生物、/猜合成、/迷雾、/快答、/宾果 或 /找茬 参与游戏吧！";
        }
        StringBuilder reply = new StringBuilder("🏆 MC 猜谜排行榜（按总胜场）");
        for (int i = 0; i < top.size(); i++) {
            McguessPlayer player = top.get(i);
            reply.append('\n').append(i + 1).append(". ")
                    .append(player.getNickname() == null || player.getNickname().isBlank()
                            ? "QQ " + player.getQq()
                            : player.getNickname())
                    .append(" — 胜 ").append(player.wins())
                    .append(" / 参与 ").append(player.played())
                    .append(" · 图鉴 ").append(player.collectionSize())
                    .append(" · 比大小最佳 ").append(player.getHolBest());
        }
        return reply.toString();
    }

    // ---------------------------------------------------------------- 用户端 HTTP

    public McguessPlayerView myStatsView(String userId) {
        return players.findByUserId(userId).map(this::toPlayerView).orElse(null);
    }

    // ---------------------------------------------------------------- 管理端 HTTP

    public McguessOverview overview() {
        return new McguessOverview(
                modeStats(itemGames.countAll(), itemGames.count(McguessGame.STATUS_PLAYING),
                        itemGames.count(McguessGame.STATUS_WON)),
                modeStats(mobGames.countAll(), mobGames.count(MobGame.STATUS_PLAYING),
                        mobGames.count(MobGame.STATUS_WON)),
                modeStats(recipeGames.countAll(), recipeGames.count(RecipeGame.STATUS_PLAYING),
                        recipeGames.count(RecipeGame.STATUS_WON)),
                modeStats(fogGames.countAll(), fogGames.count(FogGame.STATUS_PLAYING),
                        fogGames.count(FogGame.STATUS_WON)),
                modeStats(quizGames.countAll(), quizGames.count(QuizGame.STATUS_PLAYING),
                        quizGames.count(QuizGame.STATUS_WON)),
                modeStats(bingoGames.countAll(), bingoGames.count(BingoGame.STATUS_PLAYING),
                        bingoGames.count(BingoGame.STATUS_WON)),
                modeStats(spotGames.countAll(), spotGames.count(SpotGame.STATUS_PLAYING),
                        spotGames.count(SpotGame.STATUS_WON)),
                players.count(),
                catalog.items().size(), catalog.craftableCount(), catalog.guessTargetCount(),
                mobCatalog.mobs().size(), mobCatalog.conditions().size());
    }

    /**
     * 七种模式对局的统一分页查询。mode 为空时合并七库结果按开始时间倒序内存分页。
     */
    public Paged<McguessGameView> searchGames(String mode, String status, int page, int size) {
        if (McguessMode.ITEM.equals(mode)) {
            return new Paged<>(itemGames.search(status, page, size).stream().map(this::toItemView).toList(),
                    itemGames.count(status));
        }
        if (McguessMode.MOB.equals(mode)) {
            return new Paged<>(mobGames.search(status, page, size).stream().map(this::toMobView).toList(),
                    mobGames.count(status));
        }
        if (McguessMode.RECIPE.equals(mode)) {
            return new Paged<>(recipeGames.search(status, page, size).stream().map(this::toRecipeView).toList(),
                    recipeGames.count(status));
        }
        if (McguessMode.FOG.equals(mode)) {
            return new Paged<>(fogGames.search(status, page, size).stream().map(this::toFogView).toList(),
                    fogGames.count(status));
        }
        if (McguessMode.QUIZ.equals(mode)) {
            return new Paged<>(quizGames.search(status, page, size).stream().map(this::toQuizView).toList(),
                    quizGames.count(status));
        }
        if (McguessMode.BINGO.equals(mode)) {
            return new Paged<>(bingoGames.search(status, page, size).stream().map(this::toBingoView).toList(),
                    bingoGames.count(status));
        }
        if (McguessMode.SPOT.equals(mode)) {
            return new Paged<>(spotGames.search(status, page, size).stream().map(this::toSpotView).toList(),
                    spotGames.count(status));
        }
        int fetch = Math.min(Math.max(page * size, size), MERGE_FETCH_LIMIT);
        List<McguessGameView> merged = new ArrayList<>();
        itemGames.search(status, 1, fetch).forEach(game -> merged.add(toItemView(game)));
        mobGames.search(status, 1, fetch).forEach(game -> merged.add(toMobView(game)));
        recipeGames.search(status, 1, fetch).forEach(game -> merged.add(toRecipeView(game)));
        fogGames.search(status, 1, fetch).forEach(game -> merged.add(toFogView(game)));
        quizGames.search(status, 1, fetch).forEach(game -> merged.add(toQuizView(game)));
        bingoGames.search(status, 1, fetch).forEach(game -> merged.add(toBingoView(game)));
        spotGames.search(status, 1, fetch).forEach(game -> merged.add(toSpotView(game)));
        merged.sort(Comparator.comparingLong(McguessGameView::startedAt).reversed());
        long total = itemGames.count(status) + mobGames.count(status) + recipeGames.count(status)
                + fogGames.count(status) + quizGames.count(status) + bingoGames.count(status)
                + spotGames.count(status);
        int from = Math.min((page - 1) * size, merged.size());
        int to = Math.min(from + size, merged.size());
        return new Paged<>(merged.subList(from, to), total);
    }

    public Paged<McguessPlayerView> searchPlayers(int page, int size) {
        List<McguessPlayerView> records = players.search(page, size).stream().map(this::toPlayerView).toList();
        return new Paged<>(records, players.count());
    }

    // ---------------------------------------------------------------- 内部支撑

    private McguessOverview.ModeStats modeStats(long total, long playing, long won) {
        return new McguessOverview.ModeStats(total, playing, won);
    }

    private McguessGameView toItemView(McguessGame game) {
        return new McguessGameView(game.getId(), McguessMode.ITEM, McguessMode.zh(McguessMode.ITEM),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                zhOf(game.getTargetId()), game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toMobView(MobGame game) {
        return new McguessGameView(game.getId(), McguessMode.MOB, McguessMode.zh(McguessMode.MOB),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                game.filledCount() + "/" + MobGame.CELL_COUNT, game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toRecipeView(RecipeGame game) {
        return new McguessGameView(game.getId(), McguessMode.RECIPE, McguessMode.zh(McguessMode.RECIPE),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                zhOf(game.getTargetId()), game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toFogView(FogGame game) {
        return new McguessGameView(game.getId(), McguessMode.FOG, McguessMode.zh(McguessMode.FOG),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                zhOf(game.getTargetId()), game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toQuizView(QuizGame game) {
        int solved = 0;
        for (int i = 0; i < game.questionCount(); i++) {
            if (game.isSolved(i)) {
                solved++;
            }
        }
        return new McguessGameView(game.getId(), McguessMode.QUIZ, McguessMode.zh(McguessMode.QUIZ),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                solved + "/" + game.questionCount(), game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toBingoView(BingoGame game) {
        return new McguessGameView(game.getId(), McguessMode.BINGO, McguessMode.zh(McguessMode.BINGO),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                game.claimedCount() + "/" + BingoGame.CELL_COUNT, game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessGameView toSpotView(SpotGame game) {
        return new McguessGameView(game.getId(), McguessMode.SPOT, McguessMode.zh(McguessMode.SPOT),
                game.getConnectionId(), game.getPlatform(), game.getChannelId(),
                zhOf(game.getTargetId()), game.getStatus(), game.getGuesses().size(),
                game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private McguessPlayerView toPlayerView(McguessPlayer player) {
        return new McguessPlayerView(player.getUserId(), player.getQq(), player.getNickname(),
                player.getItemPlayed(), player.getItemWins(), player.getMobPlayed(), player.getMobWins(),
                player.getRecipePlayed(), player.getRecipeWins(),
                player.getFogPlayed(), player.getFogWins(), player.getQuizPlayed(), player.getQuizWins(),
                player.getBingoPlayed(), player.getBingoWins(), player.getSpotPlayed(), player.getSpotWins(),
                player.getTotalGuesses(), player.getHolBest(), player.collectionSize(), player.getUpdatedAt());
    }

    private String zhOf(String itemId) {
        return catalog.byId(itemId).map(McItem::zh).orElse(itemId);
    }
}
