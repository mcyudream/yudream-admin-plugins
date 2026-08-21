package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McRecipe;
import online.yudream.base.plugin.mcguess.domain.McguessGame;
import online.yudream.base.plugin.mcguess.domain.McguessGame.McGuess;
import online.yudream.base.plugin.mcguess.domain.McguessGameRepository;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 猜物用例编排（群回合制）：随机出题，群内共享进度。
 * 规则复刻「猜盐 · Minecraft 猜物」：猜测区域是目标的 3x3 合成配方，
 * 猜中配方树内的物品会在对应格子揭示；连续 6 次空猜解锁一次提示；
 * 直接猜出目标即胜利，/结束猜物 投降揭晓，结束后可立即再开新局。
 */
public class McguessAppService {

    /** 棋盘上展示的最近猜测记录条数。 */
    private static final int RECORD_LIMIT = 10;
    /** 距离大于该值时记录显示「远」。 */
    private static final int FAR_DISTANCE = 4;

    private final McguessGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final Random random = new Random();

    public McguessAppService(McguessGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String guess(PluginEvent event, Long userId, String rawInput) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与猜物。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            McguessGame game = activeGame(event, userIdString);
            McItem target = catalog.byId(game.getTargetId()).orElseThrow();
            List<McItem> candidates = catalog.match(rawInput);
            if (candidates.isEmpty()) {
                return "没有找到 1.20.5 中名为「" + rawInput.trim() + "」的物品。"
                        + "\n支持智能匹配：可忽略颜色词（红色/白色…）、主世界木质词（橡木/云杉…）与材质词（染色/磨制/切制）。";
            }
            McCatalog.TreeInfo tree = catalog.treeOf(game.getTargetId());
            McItem matched = pickCandidate(candidates, game, tree);
            if (game.hasGuessed(matched.id())) {
                return "「" + matched.zh() + "」本局已经猜过了，换个物品试试。"
                        + (candidates.size() > 1 ? "\n（你的输入匹配到多个物品，可写更完整的名字，如「" + candidates.getFirst().zh() + "」）" : "");
            }
            long now = System.currentTimeMillis();
            if (matched.id().equals(game.getTargetId())) {
                McGuess record = new McGuess(rawInput.trim(), matched.id(), matched.zh(), McGuess.RESULT_WIN,
                        0, tree.occurrencesOf(matched.id()), event.userId(), userIdString, now);
                game.addGuess(record);
                game.win(event.userId(), userIdString, now);
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                support.recordGameEnd(McguessMode.ITEM, participants(game), userIdString);
                List<String> added = support.collect(userIdString, event.userId(), List.of(target.id()));
                return "🎉 恭喜 QQ " + event.userId() + " 猜中目标「" + target.zh() + "」！"
                        + "\n本局共猜测 " + game.getGuesses().size() + " 次，使用提示 " + game.getHintsUsed() + " 次。"
                        + collectionSuffix(added)
                        + "\n发送 /猜物 立即开始新一局！";
            }
            Integer distance = tree.distanceOf(matched.id());
            boolean inTree = distance != null;
            String result = inTree ? McGuess.RESULT_FOUND : McGuess.RESULT_MISS;
            McGuess record = new McGuess(rawInput.trim(), matched.id(), matched.zh(), result,
                    distance, tree.occurrencesOf(matched.id()), event.userId(), userIdString, now);
            game.addGuess(record);
            StringBuilder reply = new StringBuilder();
            if (inTree) {
                game.resetEmptyStreak();
                boolean onGrid = distance == 1;
                if (onGrid) {
                    game.reveal(matched.id());
                }
                reply.append("✅ 「").append(matched.zh()).append("」在目标的配方树中，");
                if (distance <= FAR_DISTANCE) {
                    reply.append("距离目标 ").append(distance).append(" 步");
                } else {
                    reply.append("但距离目标较远");
                }
                reply.append("，配方树中出现 ×").append(tree.occurrencesOf(matched.id()));
                reply.append(onGrid ? "，已揭示到配方格！" : "。");
            } else {
                game.increaseEmptyStreak();
                reply.append("❌ 「").append(matched.zh()).append("」与目标无关（远）。");
                if (game.hintAvailable()) {
                    reply.append("\n已连续空猜 ").append(game.getEmptyStreak()).append(" 次，发送 /猜物提示 可获得一次提示！");
                } else {
                    reply.append("（连续空猜 ").append(game.getEmptyStreak())
                            .append("/").append(McguessGame.HINT_EMPTY_STREAK).append(" 解锁提示）");
                }
            }
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            if (candidates.size() > 1) {
                reply.append("\n（已按「").append(matched.zh()).append("」匹配你的输入）");
            }
            return reply.toString();
        }
    }

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            McguessGame game = latestGame(event);
            McRecipe recipe = catalog.recipeOf(game.getTargetId()).orElse(null);
            long gridTotal = recipe == null ? 0 : recipe.grid().stream().filter(Objects::nonNull).distinct().count();
            if (!game.isPlaying()) {
                return "🏁 上一局目标「" + zhOf(game.getTargetId()) + "」已揭晓（共猜 " + game.getGuesses().size()
                        + " 次）。发送 /猜物 <物品名> 开始新一局！";
            }
            return "🎯 MC 猜物进行中：已猜 " + game.getGuesses().size() + " 次，配方格已揭示 "
                    + game.getRevealed().size() + "/" + gridTotal + "。"
                    + "\n发送 /猜物 <物品名> 参与，/猜物格子 <1-9> 查看已揭示格的配方，/结束猜物 投降揭晓。";
        }
    }

    /**
     * 查看目标配方某一格（1-9）中物品的合成配方。
     * text 为兜底/错误文本；variables 非空时可渲染格子配方图片。
     */
    public CellRecipeView cellRecipe(PluginEvent event, int index) {
        if (event.channelId() == null || event.connectionId() == null) {
            return new CellRecipeView("仅支持在群聊中操作。", null);
        }
        if (index < 1 || index > 9) {
            return new CellRecipeView("格子编号需要在 1-9 之间（3x3 配方从左到右、从上到下）。", null);
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            McguessGame game = activeGame(event, null);
            Optional<McRecipe> recipe = catalog.recipeOf(game.getTargetId());
            if (recipe.isEmpty()) {
                return new CellRecipeView("本局目标没有配方数据，请联系管理员。", null);
            }
            String cellItem = recipe.get().grid().get(index - 1);
            if (cellItem == null) {
                return new CellRecipeView("第 " + index + " 格是空的（目标配方在该位置没有原料）。", null);
            }
            boolean visible = !game.isPlaying() || game.getRevealed().contains(cellItem);
            if (!visible) {
                return new CellRecipeView("第 " + index + " 格的物品尚未揭示，先把它猜出来吧！", null);
            }
            McItem item = catalog.byId(cellItem).orElseThrow();
            Optional<McRecipe> cellRecipe = catalog.recipeOf(cellItem);
            if (cellRecipe.isEmpty()) {
                return new CellRecipeView("「" + item.zh() + "」没有合成配方（无法通过合成获得）。", null);
            }
            Map<String, Object> variables = new HashMap<>();
            variables.put("itemZh", item.zh());
            variables.put("itemIcon", icons.dataUri(item.id()));
            variables.put("count", cellRecipe.get().count());
            variables.put("cells", recipeCells(cellRecipe.get(), true, null));
            variables.put("cellIndex", index);
            return new CellRecipeView("🧩 第 " + index + " 格「" + item.zh() + "」的合成配方：", variables);
        }
    }

    public String hint(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<McguessGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的猜物对局，发送 /猜物 <物品名> 开始新一局！";
            }
            McguessGame game = found.get();
            if (!game.hintAvailable()) {
                return "还没有可用的提示：需要连续空猜 " + McguessGame.HINT_EMPTY_STREAK
                        + " 次（当前 " + game.getEmptyStreak() + "/" + McguessGame.HINT_EMPTY_STREAK + "）。";
            }
            Optional<McRecipe> recipe = catalog.recipeOf(game.getTargetId());
            if (recipe.isEmpty()) {
                return "本局目标没有配方数据，请联系管理员。";
            }
            List<String> hidden = new ArrayList<>();
            for (String ingredient : recipe.get().ingredients()) {
                if (!game.getRevealed().contains(ingredient) && !hidden.contains(ingredient)) {
                    hidden.add(ingredient);
                }
            }
            if (hidden.isEmpty()) {
                return "配方格已经全部揭示了，直接猜目标物品吧！";
            }
            String picked = hidden.get(random.nextInt(hidden.size()));
            game.reveal(picked);
            game.useHint();
            games.save(game);
            return "💡 提示生效：目标的配方中有一格是「" + zhOf(picked) + "」！";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<McguessGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的猜物对局，发送 /猜物 <物品名> 开始新一局！";
            }
            McguessGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.ITEM, participants(game), null);
            return "🏳️ 本局猜物已结束，目标是「" + zhOf(game.getTargetId()) + "」（共猜 "
                    + game.getGuesses().size() + " 次）。发送 /猜物 开始新一局！";
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
            McguessGame game = latestGame(event);
            boolean won = McguessGame.STATUS_WON.equals(game.getStatus());
            boolean lost = McguessGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;
            McRecipe recipe = catalog.recipeOf(game.getTargetId()).orElse(null);
            McItem target = catalog.byId(game.getTargetId()).orElse(null);

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 猜物");
            variables.put("subtitle", "随机出题 · 群内回合制 · JE 1.20.5");
            variables.put("cells", recipe == null ? List.of() : recipeCells(recipe, ended ? true : null, game));
            variables.put("gridRevealed", game.getRevealed().size());
            variables.put("gridTotal", recipe == null ? 0
                    : recipe.grid().stream().filter(Objects::nonNull).distinct().count());
            variables.put("won", won);
            variables.put("lost", lost);
            variables.put("ended", ended);
            variables.put("targetZh", ended && target != null ? target.zh() : "？？？");
            variables.put("targetIcon", ended && target != null ? icons.dataUri(target.id()) : null);
            variables.put("targetCount", recipe == null ? 1 : recipe.count());
            variables.put("guessCount", game.getGuesses().size());
            variables.put("hintsUsed", game.getHintsUsed());
            variables.put("emptyStreak", game.getEmptyStreak());
            variables.put("hintNeed", McguessGame.HINT_EMPTY_STREAK);
            variables.put("hintAvailable", game.hintAvailable());
            variables.put("winnerQq", game.getWinnerQq());
            variables.put("records", recordRows(game));
            variables.put("banner", banner);
            return variables;
        }
    }

    // ---------------------------------------------------------------- 内部支撑

    /** 本群当前进行中的对局；不存在时随机出题开新局（startedByUserId 为空时仅记开局 QQ）。 */
    private McguessGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<McguessGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        McItem target = catalog.randomTarget(random);
        McguessGame game = new McguessGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(), target.id(),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    /** 查看场景：取该群最近一局（含刚结束），从未有过对局时才开新局。 */
    private McguessGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    private List<McguessSupport.Participant> participants(McguessGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (McGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    /** 多个候选时的取舍：优先目标本身，其次配方树中距离最近的，最后取数据集中的第一个。 */
    private McItem pickCandidate(List<McItem> candidates, McguessGame game, McCatalog.TreeInfo tree) {
        for (McItem candidate : candidates) {
            if (candidate.id().equals(game.getTargetId())) {
                return candidate;
            }
        }
        McItem best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (McItem candidate : candidates) {
            Integer distance = tree.distanceOf(candidate.id());
            if (distance != null && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best != null ? best : candidates.getFirst();
    }

    /**
     * 3x3 配方格渲染变量。
     *
     * @param revealAll true 全部揭示；null 结合 game 的已揭示集合
     */
    private List<Map<String, Object>> recipeCells(McRecipe recipe, Boolean revealAll, McguessGame game) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String itemId = recipe.grid().get(i);
            Map<String, Object> cell = new HashMap<>();
            cell.put("index", i + 1);
            if (itemId == null) {
                cell.put("state", "empty");
            } else {
                boolean shown = Boolean.TRUE.equals(revealAll)
                        || (revealAll == null && game != null && game.getRevealed().contains(itemId));
                if (shown) {
                    McItem item = catalog.byId(itemId).orElse(null);
                    cell.put("state", "shown");
                    cell.put("zh", item == null ? itemId : item.zh());
                    cell.put("icon", icons.dataUri(itemId));
                } else {
                    cell.put("state", "hidden");
                }
            }
            cells.add(cell);
        }
        return cells;
    }

    private List<Map<String, Object>> recordRows(McguessGame game) {
        List<McGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            McGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("zh", guess.matchedZh());
            row.put("qq", guess.qq());
            row.put("icon", icons.dataUri(guess.matchedId()));
            switch (guess.result()) {
                case McGuess.RESULT_WIN -> {
                    row.put("cls", "win");
                    row.put("label", "🎉 猜中");
                }
                case McGuess.RESULT_FOUND -> {
                    row.put("cls", "found");
                    Integer distance = guess.distance();
                    row.put("label", distance == null || distance > FAR_DISTANCE ? "远" : "距离 " + distance);
                }
                default -> {
                    row.put("cls", "miss");
                    row.put("label", "远");
                }
            }
            row.put("occurrences", guess.occurrences());
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

    /**
     * 格子配方查看结果：text 为群聊兜底文本，variables 非空时可渲染配方图片。
     */
    public record CellRecipeView(String text, Map<String, Object> variables) {
    }
}
