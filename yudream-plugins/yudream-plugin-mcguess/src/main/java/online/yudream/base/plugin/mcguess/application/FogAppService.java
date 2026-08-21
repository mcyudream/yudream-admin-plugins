package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.FogGame;
import online.yudream.base.plugin.mcguess.domain.FogGame.FogGuess;
import online.yudream.base.plugin.mcguess.domain.FogGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 迷雾猜物用例编排（群回合制）：随机选定一个带图标物品，图标被迷雾遮蔽，
 * 每次猜错迷雾散去一分（阶段 0-5），直接猜中目标即获胜，/结束迷雾 投降揭晓。
 */
public class FogAppService {

    /** 棋盘上展示的最近猜测记录条数。 */
    private static final int RECORD_LIMIT = 10;

    /** 各迷雾阶段的图标 CSS 滤镜（0 纯剪影 → 5 完全清晰）。 */
    private static final List<String> STAGE_FILTERS = List.of(
            "brightness(0)",
            "brightness(0.2) blur(7px)",
            "brightness(0.35) blur(5px)",
            "brightness(0.55) blur(3px)",
            "brightness(0.75) blur(1.5px)",
            "none");

    private final FogGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final Random random = new Random();

    public FogAppService(FogGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String guess(PluginEvent event, Long userId, String rawInput) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与迷雾猜物。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            FogGame game = activeGame(event, userIdString);
            List<McItem> candidates = catalog.match(rawInput);
            if (candidates.isEmpty()) {
                return "没有找到 1.20.5 中名为「" + rawInput.trim() + "」的物品。"
                        + "\n支持智能匹配：可忽略颜色词（红色/白色…）、主世界木质词（橡木/云杉…）与材质词（染色/磨制/切制）。";
            }
            McItem matched = pickCandidate(candidates, game);
            if (game.hasGuessed(matched.id())) {
                return "「" + matched.zh() + "」本局已经猜过了，换个物品试试。";
            }
            long now = System.currentTimeMillis();
            if (matched.id().equals(game.getTargetId())) {
                game.addGuess(new FogGuess(rawInput.trim(), matched.id(), matched.zh(),
                        FogGuess.RESULT_WIN, event.userId(), userIdString, now));
                game.win(event.userId(), userIdString, now);
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                support.recordGameEnd(McguessMode.FOG, participants(game), userIdString);
                List<String> added = support.collect(userIdString, event.userId(), List.of(game.getTargetId()));
                return "🎉 恭喜 QQ " + event.userId() + " 看破迷雾，猜中目标「" + matched.zh() + "」！"
                        + "\n本局共猜测 " + game.getGuesses().size() + " 次，迷雾停在阶段 " + game.getStage() + "/"
                        + FogGame.MAX_STAGE + "。" + collectionSuffix(added)
                        + "\n发送 /迷雾 立即开始新一局！";
            }
            game.stageUp();
            game.addGuess(new FogGuess(rawInput.trim(), matched.id(), matched.zh(),
                    FogGuess.RESULT_MISS, event.userId(), userIdString, now));
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            return "❌ 不是「" + matched.zh() + "」，迷雾散去了一些（阶段 " + game.getStage() + "/"
                    + FogGame.MAX_STAGE + (game.getStage() >= FogGame.MAX_STAGE ? "，图标已完全清晰" : "") + "）。";
        }
    }

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            FogGame game = latestGame(event);
            if (!game.isPlaying()) {
                return "🏁 上一局迷雾已结束，目标是「" + zhOf(game.getTargetId()) + "」。发送 /迷雾 开始新一局！";
            }
            return "🌫️ MC 迷雾猜物进行中：已猜 " + game.getGuesses().size() + " 次，迷雾阶段 "
                    + game.getStage() + "/" + FogGame.MAX_STAGE + "。"
                    + "\n发送 /迷雾 <物品名> 参与，/结束迷雾 投降揭晓。";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<FogGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的迷雾对局，发送 /迷雾 开始新一局！";
            }
            FogGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.FOG, participants(game), null);
            return "🏳️ 本局迷雾已结束，目标是「" + zhOf(game.getTargetId()) + "」（共猜 "
                    + game.getGuesses().size() + " 次）。发送 /迷雾 开始新一局！";
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    /**
     * 本群当前对局的棋盘渲染变量快照；不在群聊中时返回 null。
     * 在渠道锁内构建，避免模板异步渲染期间对局被并发修改。
     */
    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            FogGame game = latestGame(event);
            boolean won = FogGame.STATUS_WON.equals(game.getStatus());
            boolean lost = FogGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;
            McItem target = catalog.byId(game.getTargetId()).orElse(null);

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 迷雾猜物");
            variables.put("subtitle", "看破迷雾 · 认出图标 · JE 1.20.5");
            variables.put("targetZh", ended && target != null ? target.zh() : "？？？");
            variables.put("targetIcon", target == null ? null : icons.dataUri(target.id()));
            variables.put("iconFilter", ended ? "none" : STAGE_FILTERS.get(game.getStage()));
            variables.put("stage", game.getStage());
            variables.put("maxStage", FogGame.MAX_STAGE);
            variables.put("guessCount", game.getGuesses().size());
            variables.put("won", won);
            variables.put("lost", lost);
            variables.put("ended", ended);
            variables.put("winnerQq", game.getWinnerQq());
            variables.put("records", recordRows(game));
            variables.put("banner", banner);
            return variables;
        }
    }

    // ---------------------------------------------------------------- 内部支撑

    /** 本群当前进行中的对局；不存在时随机开新局（startedByUserId 为空时仅记开局 QQ）。 */
    private FogGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<FogGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        List<McItem> pool = catalog.iconItems();
        McItem target = pool.get(random.nextInt(pool.size()));
        FogGame game = new FogGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(), target.id(),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    /** 查看场景：取该群最近一局（含刚结束），从未有过对局时才开新局。 */
    private FogGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    /** 多个候选时的取舍：优先目标本身，其次数据集中的第一个。 */
    private McItem pickCandidate(List<McItem> candidates, FogGame game) {
        for (McItem candidate : candidates) {
            if (candidate.id().equals(game.getTargetId())) {
                return candidate;
            }
        }
        return candidates.getFirst();
    }

    private List<McguessSupport.Participant> participants(FogGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (FogGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(FogGame game) {
        List<FogGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            FogGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("zh", guess.matchedZh());
            row.put("qq", guess.qq());
            row.put("icon", guess.matchedId() == null ? null : icons.dataUri(guess.matchedId()));
            if (FogGuess.RESULT_WIN.equals(guess.result())) {
                row.put("cls", "found");
                row.put("label", "🎉 猜中");
            } else {
                row.put("cls", "miss");
                row.put("label", "不对");
            }
            rows.add(row);
        }
        return rows;
    }

    private String collectionSuffix(List<String> added) {
        if (added.isEmpty()) {
            return "";
        }
        return "\n🎴 图鉴新增：" + added.stream().map(id -> "「" + zhOf(id) + "」").collect(Collectors.joining(""));
    }

    private String zhOf(String itemId) {
        return catalog.byId(itemId).map(McItem::zh).orElse(itemId);
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }
}
