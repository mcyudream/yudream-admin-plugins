package online.yudream.base.plugin.wordle.application;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.wordle.application.dto.Paged;
import online.yudream.base.plugin.wordle.application.dto.WordEntryView;
import online.yudream.base.plugin.wordle.application.dto.WordleGameView;
import online.yudream.base.plugin.wordle.application.dto.WordleOverview;
import online.yudream.base.plugin.wordle.application.dto.WordlePlayerView;
import online.yudream.base.plugin.wordle.domain.Guess;
import online.yudream.base.plugin.wordle.domain.LetterState;
import online.yudream.base.plugin.wordle.domain.WordEntry;
import online.yudream.base.plugin.wordle.domain.WordEntryRepository;
import online.yudream.base.plugin.wordle.domain.WordleEvaluator;
import online.yudream.base.plugin.wordle.domain.WordleGame;
import online.yudream.base.plugin.wordle.domain.WordleGameRepository;
import online.yudream.base.plugin.wordle.domain.WordleMode;
import online.yudream.base.plugin.wordle.domain.WordlePlayer;
import online.yudream.base.plugin.wordle.domain.WordlePlayerRepository;
import online.yudream.base.plugin.wordle.infrastructure.WordBank;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WordleAppService {

    private final WordleGameRepository games;
    private final WordlePlayerRepository players;
    private final WordEntryRepository words;
    private final WordBank wordBank;
    private final FrameworkServices framework;
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    public WordleAppService(WordleGameRepository games, WordlePlayerRepository players,
                            WordEntryRepository words, WordBank wordBank, FrameworkServices framework) {
        this.games = games;
        this.players = players;
        this.words = words;
        this.wordBank = wordBank;
        this.framework = framework;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String startGame(PluginEvent event, Long userId, WordleMode mode, Integer lengthArg, boolean hardMode) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中开始猜词游戏。";
        }
        int length = lengthArg == null ? (mode == WordleMode.IDIOM ? 4 : 5) : lengthArg;
        if (length < 3 || length > 10) {
            return "词长需要在 3-10 之间。";
        }
        synchronized (lockFor(event)) {
            Optional<WordleGame> existing = games.findActive(event.connectionId(), event.channelId());
            if (existing.isPresent()) {
                WordleGame game = existing.get();
                return "本群已有一局进行中的对局（" + describe(game) + "，已猜 " + game.getGuesses().size()
                        + "/" + game.getMaxGuesses() + " 次）。发送 /猜词状态 查看进度，或 /结束猜词 揭晓答案。";
            }
            Optional<String> answer = wordBank.randomAnswer(mode, length);
            if (answer.isEmpty()) {
                return "「" + mode.label() + "」模式暂无长度为 " + length + " 的词条，请联系管理员在词库管理中添加。";
            }
            WordleGame game = new WordleGame(UUID.randomUUID().toString(), event.connectionId(), event.platform(),
                    event.channelId(), mode, answer.get(), hardMode, event.userId(), userIdString(userId), System.currentTimeMillis());
            games.save(game);
            return "🎮 猜词游戏开始！" + describe(game)
                    + "\n发送 /猜 <" + (mode == WordleMode.IDIOM ? "成语" : "单词") + "> 参与竞猜，/猜 随机 可由词库随机挑一个；/猜词状态 查看进度，/结束猜词 投降揭晓。";
        }
    }

    public String guess(PluginEvent event, Long userId, String rawWord) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与猜词。";
        }
        synchronized (lockFor(event)) {
            Optional<WordleGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局，发送 /猜单词 或 /猜成语 开始一局。";
            }
            WordleGame game = found.get();
            String word = WordleEvaluator.normalize(game.getMode(), rawWord);
            if (word.equals("随机") || word.equalsIgnoreCase("random")) {
                List<String> guessed = game.getGuesses().stream().map(Guess::word).toList();
                Optional<String> picked = wordBank.randomGuess(game.getMode(), game.length(), guessed);
                if (picked.isEmpty()) {
                    return "词库里已经没有未猜过的候选词了，靠你自己啦！";
                }
                word = picked.get();
            }
            if (!WordleEvaluator.isValidGuess(game.getMode(), word, game.length())) {
                return game.getMode() == WordleMode.IDIOM
                        ? "请输入 4 个汉字组成的成语。"
                        : "请输入 " + game.length() + " 个英文字母组成的单词。";
            }
            if (game.hasGuessed(word)) {
                return "「" + word + "」已经猜过了，换个词试试。";
            }
            if (game.isHardMode()) {
                String violation = WordleEvaluator.checkHardMode(game, word);
                if (violation != null) {
                    return violation;
                }
            }
            List<LetterState> states = WordleEvaluator.evaluate(game.getAnswer(), word);
            Guess guess = new Guess(word, states, userIdString(userId), event.userId(), System.currentTimeMillis());
            game.addGuess(guess);
            String line = guess.tiles() + "  " + word;
            if (WordleEvaluator.isSolved(states)) {
                game.win(event.userId(), userIdString(userId), System.currentTimeMillis());
                games.save(game);
                recordStats(game, true);
                StringBuilder reply = new StringBuilder(line)
                        .append("\n🎉 恭喜 QQ ").append(event.userId()).append(" 在第 ").append(game.getGuesses().size())
                        .append(" 次猜中答案「").append(game.getAnswer()).append("」！");
                wordBank.hintOf(game.getMode(), game.getAnswer()).ifPresent(hint -> reply.append("\n释义：").append(hint));
                return reply.toString();
            }
            if (game.remaining() <= 0) {
                game.lose(System.currentTimeMillis());
                games.save(game);
                recordStats(game, false);
                StringBuilder reply = new StringBuilder(line)
                        .append("\n😢 次数用尽，答案是「").append(game.getAnswer()).append("」。再接再厉！");
                wordBank.hintOf(game.getMode(), game.getAnswer()).ifPresent(hint -> reply.append("\n释义：").append(hint));
                return reply.toString();
            }
            games.save(game);
            return line + "\n第 " + game.getGuesses().size() + "/" + game.getMaxGuesses() + " 次，还剩 " + game.remaining() + " 次机会。";
        }
    }

    public String endGame(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (lockFor(event)) {
            Optional<WordleGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局。";
            }
            WordleGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            recordStats(game, false);
            StringBuilder reply = new StringBuilder("🏳️ 对局已结束，答案是「").append(game.getAnswer()).append("」。");
            wordBank.hintOf(game.getMode(), game.getAnswer()).ifPresent(hint -> reply.append("\n释义：").append(hint));
            return reply.toString();
        }
    }

    public String gameStatus(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (lockFor(event)) {
            Optional<WordleGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "本群当前没有进行中的对局，发送 /猜单词 或 /猜成语 开始一局。";
            }
            WordleGame game = found.get();
            StringBuilder reply = new StringBuilder("🎮 进行中的对局：").append(describe(game))
                    .append("\n进度：").append(game.getGuesses().size()).append("/").append(game.getMaxGuesses());
            for (String line : WordleEvaluator.renderBoard(game)) {
                reply.append("\n").append(line);
            }
            if (game.getGuesses().isEmpty()) {
                reply.append("\n还没有人猜过，发送 /猜 <").append(game.getMode() == WordleMode.IDIOM ? "成语" : "单词").append("> 抢首猜！");
            }
            return reply.toString();
        }
    }

    public String myStats(Long userId) {
        if (userId == null) {
            return "请先使用 /绑定 绑定系统账号后再查看战绩。";
        }
        Optional<WordlePlayer> found = players.findByUserId(String.valueOf(userId));
        if (found.isEmpty()) {
            return "你还没有猜词战绩，去群里发送 /猜单词 或 /猜成语 开局吧！";
        }
        WordlePlayer player = found.get();
        StringBuilder reply = new StringBuilder("📊 你的猜词战绩")
                .append("\n英文单词：").append(player.getEnglishPlayed()).append(" 局 / 胜 ")
                .append(player.getEnglishWins()).append("（胜率 ").append(winRate(player.getEnglishPlayed(), player.getEnglishWins())).append("）")
                .append("\n四字成语：").append(player.getIdiomPlayed()).append(" 局 / 胜 ")
                .append(player.getIdiomWins()).append("（胜率 ").append(winRate(player.getIdiomPlayed(), player.getIdiomWins())).append("）")
                .append("\n当前连胜 ").append(player.getCurrentStreak()).append(" · 最高连胜 ").append(player.getBestStreak());
        if (!player.getWinDistribution().isEmpty()) {
            List<String> parts = new ArrayList<>();
            player.getWinDistribution().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> parts.add(entry.getKey() + " 次×" + entry.getValue()));
            reply.append("\n猜中分布：").append(String.join("，", parts));
        }
        return reply.toString();
    }

    public String leaderboard() {
        List<WordlePlayer> top = players.search(1, 10);
        if (top.isEmpty()) {
            return "还没有玩家战绩，发送 /猜单词 或 /猜成语 开打第一局！";
        }
        StringBuilder reply = new StringBuilder("🏆 猜词排行榜（按总胜场）");
        int rank = 1;
        for (WordlePlayer player : top) {
            if (player.totalPlayed() <= 0) {
                continue;
            }
            reply.append("\n").append(rank++).append(". ").append(displayName(player))
                    .append("  胜 ").append(player.totalWins()).append(" / 玩 ").append(player.totalPlayed())
                    .append("（胜率 ").append(winRate(player.totalPlayed(), player.totalWins())).append("）");
        }
        return reply.toString();
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    /**
     * 群内最近一局对局的棋盘渲染变量快照；本群从未开局时返回 null。
     * 在渠道锁内构建，避免模板异步渲染期间猜测列表被并发修改。
     */
    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (lockFor(event)) {
            Optional<WordleGame> found = games.findLatest(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return null;
            }
            WordleGame game = found.get();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Guess guess : game.getGuesses()) {
                boolean solved = WordleEvaluator.isSolved(guess.states());
                List<Map<String, Object>> tiles = new ArrayList<>();
                String[] chars = guess.word().codePoints()
                        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                        .toArray(String[]::new);
                for (int i = 0; i < chars.length; i++) {
                    String display = game.getMode() == WordleMode.ENGLISH ? chars[i].toUpperCase(java.util.Locale.ROOT) : chars[i];
                    tiles.add(Map.of("ch", display, "cls", "revealed " + tileClass(guess.states().get(i))));
                }
                rows.add(Map.of("solved", solved, "tiles", tiles));
            }
            for (int i = game.getGuesses().size(); i < game.getMaxGuesses(); i++) {
                List<Map<String, Object>> tiles = new ArrayList<>();
                for (int j = 0; j < game.length(); j++) {
                    tiles.add(Map.of("ch", "", "cls", "empty"));
                }
                rows.add(Map.of("solved", false, "tiles", tiles));
            }
            boolean finished = !game.isPlaying();
            Map<String, Object> variables = new java.util.HashMap<>();
            variables.put("title", game.getMode() == WordleMode.IDIOM ? "猜成语" : "猜单词");
            variables.put("subtitle", describe(game));
            variables.put("rows", rows);
            variables.put("progress", "第 " + game.getGuesses().size() + " / " + game.getMaxGuesses() + " 次");
            variables.put("statusText", statusText(game));
            variables.put("finished", finished);
            variables.put("won", WordleGame.STATUS_WON.equals(game.getStatus()));
            variables.put("answer", game.getMode() == WordleMode.ENGLISH
                    ? game.getAnswer().toUpperCase(java.util.Locale.ROOT) : game.getAnswer());
            variables.put("banner", banner);
            return variables;
        }
    }

    private String tileClass(LetterState state) {
        return switch (state) {
            case CORRECT -> "exact";
            case PRESENT -> "misplaced";
            case ABSENT -> "none";
        };
    }

    private String statusText(WordleGame game) {
        return switch (game.getStatus()) {
            case WordleGame.STATUS_WON -> "已猜中";
            case WordleGame.STATUS_LOST -> "已结束";
            default -> "进行中";
        };
    }

    // ---------------------------------------------------------------- 用户端 HTTP

    public WordlePlayerView myStatsView(String userId) {
        return players.findByUserId(userId).map(this::toPlayerView).orElse(null);
    }

    // ---------------------------------------------------------------- 管理端 HTTP

    public WordleOverview overview() {
        return new WordleOverview(games.countAll(), games.count(WordleGame.STATUS_PLAYING),
                games.count(WordleGame.STATUS_WON), words.countAll(), players.count());
    }

    public Paged<WordEntryView> searchWords(String mode, String keyword, int page, int size) {
        List<WordEntryView> records = words.search(mode, keyword, page, size).stream().map(this::toEntryView).toList();
        return new Paged<>(records, words.count(mode, keyword));
    }

    public WordEntryView createWord(String modeValue, String rawWord, String hint, String operatorUserId) {
        WordleMode mode = WordleMode.from(modeValue);
        if (mode == null) {
            throw new IllegalArgumentException("模式必须是 ENGLISH 或 IDIOM");
        }
        String word = WordleEvaluator.normalize(mode, rawWord);
        int wordLength = word.codePointCount(0, word.length());
        if (!WordleEvaluator.isValidGuess(mode, word, wordLength)) {
            throw new IllegalArgumentException(mode == WordleMode.IDIOM ? "词条内容必须是汉字" : "词条内容必须是纯英文字母");
        }
        if (mode == WordleMode.IDIOM && wordLength != 4) {
            throw new IllegalArgumentException("成语词条必须是 4 个汉字");
        }
        if (wordLength < 3 || wordLength > 10) {
            throw new IllegalArgumentException("词长需要在 3-10 之间");
        }
        if (words.findById(WordEntry.buildId(mode, word)).isPresent()) {
            throw new IllegalArgumentException("词条「" + word + "」已存在");
        }
        WordEntry entry = new WordEntry(mode, word, hint == null || hint.isBlank() ? null : hint.trim(),
                true, System.currentTimeMillis(), operatorUserId);
        words.save(entry);
        return toEntryView(entry);
    }

    public WordEntryView updateWord(String id, String hint, Boolean enabled) {
        WordEntry entry = words.findById(id).orElseThrow(() -> new IllegalArgumentException("词条不存在"));
        if (hint != null) {
            entry.setHint(hint.isBlank() ? null : hint.trim());
        }
        if (enabled != null) {
            entry.setEnabled(enabled);
        }
        words.save(entry);
        return toEntryView(entry);
    }

    public void deleteWord(String id) {
        if (words.findById(id).isEmpty()) {
            throw new IllegalArgumentException("词条不存在");
        }
        words.delete(id);
    }

    public Paged<WordleGameView> searchGames(String status, int page, int size) {
        List<WordleGameView> records = games.search(status, page, size).stream().map(this::toGameView).toList();
        return new Paged<>(records, games.count(status));
    }

    public Paged<WordlePlayerView> searchPlayers(int page, int size) {
        List<WordlePlayerView> records = players.search(page, size).stream().map(this::toPlayerView).toList();
        return new Paged<>(records, players.count());
    }

    // ---------------------------------------------------------------- 内部支撑

    private void recordStats(WordleGame game, boolean won) {
        Set<String> participantIds = new LinkedHashSet<>();
        for (Guess guess : game.getGuesses()) {
            if (guess.userId() != null && !guess.userId().isBlank()) {
                participantIds.add(guess.userId());
            }
        }
        long now = System.currentTimeMillis();
        for (String participantId : participantIds) {
            WordlePlayer player = players.findByUserId(participantId).orElseGet(() -> new WordlePlayer(participantId));
            player.setQq(lastQqOf(game, participantId));
            resolveNickname(player);
            player.recordPlayed(game.getMode(), now);
            if (won && participantId.equals(game.getWinnerUserId())) {
                player.recordWin(game.getMode(), game.getGuesses().size(), now);
            } else if (!won) {
                player.recordLoss(now);
            }
            players.save(player);
        }
    }

    private String lastQqOf(WordleGame game, String userId) {
        String qq = null;
        for (Guess guess : game.getGuesses()) {
            if (userId.equals(guess.userId())) {
                qq = guess.qq();
            }
        }
        return qq;
    }

    private void resolveNickname(WordlePlayer player) {
        try {
            framework.users().findById(Long.valueOf(player.getUserId())).ifPresent(profile -> {
                String nickname = profile.nickname() == null || profile.nickname().isBlank() ? profile.username() : profile.nickname();
                player.setNickname(nickname);
            });
        } catch (RuntimeException ignored) {
            // 昵称解析失败不影响战绩记录
        }
    }

    private String displayName(WordlePlayer player) {
        if (player.getNickname() != null && !player.getNickname().isBlank()) {
            return player.getNickname();
        }
        return "QQ " + (player.getQq() == null ? player.getUserId() : player.getQq());
    }

    private String describe(WordleGame game) {
        return game.getMode().label() + " · 词长 " + game.length() + " · 共 " + game.getMaxGuesses() + " 次机会"
                + (game.isHardMode() ? " · 困难模式" : "");
    }

    private String winRate(int played, int wins) {
        return played <= 0 ? "0%" : Math.round(wins * 100.0 / played) + "%";
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }

    private Object lockFor(PluginEvent event) {
        return channelLocks.computeIfAbsent(WordleGame.channelKey(event.connectionId(), event.channelId()), key -> new Object());
    }

    private WordEntryView toEntryView(WordEntry entry) {
        return new WordEntryView(entry.getId(), entry.getMode().name(), entry.getMode().label(), entry.getWord(),
                entry.length(), entry.getHint(), entry.isEnabled(), entry.getCreatedAt(), entry.getCreatedBy());
    }

    private WordleGameView toGameView(WordleGame game) {
        return new WordleGameView(game.getId(), game.getChannelId(), game.getMode().name(), game.getMode().label(),
                game.length(), game.isHardMode(), game.getStatus(), game.getMaxGuesses(), game.getGuesses().size(),
                game.getAnswer(), game.getStartedByQq(), game.getWinnerQq(), game.getStartedAt(), game.getEndedAt());
    }

    private WordlePlayerView toPlayerView(WordlePlayer player) {
        return new WordlePlayerView(player.getUserId(), player.getQq(), player.getNickname(),
                player.getEnglishPlayed(), player.getEnglishWins(), player.getIdiomPlayed(), player.getIdiomWins(),
                player.getCurrentStreak(), player.getBestStreak(), Map.copyOf(player.getWinDistribution()), player.getUpdatedAt());
    }
}
