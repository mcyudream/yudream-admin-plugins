package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McRecipe;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.domain.RecipeGame;
import online.yudream.base.plugin.mcguess.domain.RecipeGame.RecipeGuess;
import online.yudream.base.plugin.mcguess.domain.RecipeGameRepository;
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
 * 猜合成用例编排（反向玩法）：目标物品公开，玩家逐格猜它的 3x3 配方原料；
 * 猜中某格的原料会一并揭示该物品占用的全部格子；连续 6 次空猜解锁 /猜合成提示；
 * 全部原料格揭示获胜，/结束猜合成 投降揭晓，结束后可立即再开新局。
 */
public class RecipeAppService {

    /** 棋盘上展示的最近填格记录条数。 */
    private static final int RECORD_LIMIT = 10;

    private final RecipeGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final Random random = new Random();

    public RecipeAppService(RecipeGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            RecipeGame game = latestGame(event);
            if (!game.isPlaying()) {
                return "🏁 上一局猜合成已结束，目标是「" + zhOf(game.getTargetId()) + "」。发送 /猜合成 开始新一局！";
            }
            return "🧪 MC 猜合成进行中：目标是「" + zhOf(game.getTargetId()) + "」，配方原料格已揭示 "
                    + game.revealedSlotCount() + "/" + game.ingredientSlotCount() + "。"
                    + "\n发送 /猜合成 <1-9> <物品名> 填格，/结束猜合成 投降揭晓。";
        }
    }

    public String fill(PluginEvent event, Long userId, int cell, String itemName) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与猜合成。";
        }
        if (!RecipeGame.isValidCell(cell)) {
            return "格子编号需要在 1-9 之间（3x3 配方从左到右、从上到下）。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            RecipeGame game = activeGame(event, userIdString);
            List<McItem> candidates = catalog.match(itemName);
            if (candidates.isEmpty()) {
                return "没有找到 1.20.5 中名为「" + itemName.trim() + "」的物品。"
                        + "\n支持智能匹配：可忽略颜色词（红色/白色…）、主世界木质词（橡木/云杉…）与材质词（染色/磨制/切制）。";
            }
            McItem matched = pickCandidate(candidates, game, cell);
            if (game.isRevealedSlot(cell)) {
                return "第 " + cell + " 格的原料「" + zhOf(game.getGrid().get(cell - 1)) + "」已经揭示了，换个格子试试。";
            }
            long now = System.currentTimeMillis();
            if (game.isEmptySlot(cell)) {
                game.increaseEmptyStreak();
                game.addGuess(new RecipeGuess(cell, itemName.trim(), matched.id(), matched.zh(),
                        RecipeGuess.RESULT_EMPTY, event.userId(), userIdString, now));
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                return "🕳️ 第 " + cell + " 格本来就空无一物（该配方此位置没有原料），记一次空猜"
                        + emptyStreakSuffix(game);
            }
            if (game.matches(cell, matched.id())) {
                List<Integer> slots = game.revealItem(matched.id());
                game.resetEmptyStreak();
                game.addGuess(new RecipeGuess(cell, itemName.trim(), matched.id(), matched.zh(),
                        RecipeGuess.RESULT_HIT, event.userId(), userIdString, now));
                if (game.isComplete()) {
                    game.win(event.userId(), userIdString, now);
                    games.save(game);
                    support.recordGuess(userIdString, event.userId());
                    support.recordGameEnd(McguessMode.RECIPE, participants(game), userIdString);
                    List<String> added = support.collect(userIdString, event.userId(), List.of(game.getTargetId()));
                    return "🎉 恭喜 QQ " + event.userId() + " 填入「" + matched.zh() + "」，配方全部揭示，本群获胜！"
                            + "\n目标是「" + zhOf(game.getTargetId()) + "」。" + collectionSuffix(added)
                            + "\n发送 /猜合成 开始新一局！";
                }
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                return "✅ 第 " + cell + " 格正是「" + matched.zh() + "」！" + (slots.size() > 1
                        ? "它一共占了 " + slots.size() + " 格（" + slots + "），全部揭示。" : "")
                        + "\n已揭示 " + game.revealedSlotCount() + "/" + game.ingredientSlotCount() + " 格原料。";
            }
            game.increaseEmptyStreak();
            game.addGuess(new RecipeGuess(cell, itemName.trim(), matched.id(), matched.zh(),
                    RecipeGuess.RESULT_MISS, event.userId(), userIdString, now));
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            return "❌ 第 " + cell + " 格的原料不是「" + matched.zh() + "」，记一次空猜" + emptyStreakSuffix(game);
        }
    }

    public String hint(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<RecipeGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的猜合成对局，发送 /猜合成 开始新一局！";
            }
            RecipeGame game = found.get();
            if (!game.hintAvailable()) {
                return "还没有可用的提示：需要连续空猜 " + RecipeGame.HINT_EMPTY_STREAK
                        + " 次（当前 " + game.getEmptyStreak() + "/" + RecipeGame.HINT_EMPTY_STREAK + "）。";
            }
            List<String> hidden = game.unrevealedIngredients();
            if (hidden.isEmpty()) {
                return "配方原料已经全部揭示了，直接等大家填完吧！";
            }
            String picked = hidden.get(random.nextInt(hidden.size()));
            List<Integer> slots = game.revealItem(picked);
            game.useHint();
            games.save(game);
            return "💡 提示生效：第 " + slots + " 格是「" + zhOf(picked) + "」！";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<RecipeGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的猜合成对局，发送 /猜合成 开始新一局！";
            }
            RecipeGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.RECIPE, participants(game), null);
            return "🏳️ 本局猜合成已结束，目标「" + zhOf(game.getTargetId()) + "」的配方已揭晓。发送 /猜合成 开始新一局！";
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
            RecipeGame game = latestGame(event);
            boolean won = RecipeGame.STATUS_WON.equals(game.getStatus());
            boolean lost = RecipeGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;
            McItem target = catalog.byId(game.getTargetId()).orElse(null);
            McRecipe recipe = catalog.recipeOf(game.getTargetId()).orElse(null);

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 猜合成");
            variables.put("subtitle", "反向玩法 · 逐格填配方 · JE 1.20.5");
            variables.put("targetZh", target == null ? game.getTargetId() : target.zh());
            variables.put("targetIcon", target == null ? null : icons.dataUri(target.id()));
            variables.put("targetCount", recipe == null ? 1 : recipe.count());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                String itemId = game.getGrid().get(i);
                Map<String, Object> cell = new HashMap<>();
                cell.put("index", i + 1);
                if (itemId == null) {
                    cell.put("state", "empty");
                } else if (ended || game.getRevealed().contains(itemId)) {
                    cell.put("state", "shown");
                    cell.put("zh", zhOf(itemId));
                    cell.put("icon", icons.dataUri(itemId));
                } else {
                    cell.put("state", "hidden");
                }
                cells.add(cell);
            }
            variables.put("cells", cells);
            variables.put("revealedSlots", game.revealedSlotCount());
            variables.put("ingredientSlots", game.ingredientSlotCount());
            variables.put("guessCount", game.getGuesses().size());
            variables.put("hintsUsed", game.getHintsUsed());
            variables.put("emptyStreak", game.getEmptyStreak());
            variables.put("hintNeed", RecipeGame.HINT_EMPTY_STREAK);
            variables.put("hintAvailable", game.hintAvailable());
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

    /** 本群当前进行中的对局；不存在时随机出题开新局（startedByUserId 为空时仅记开局 QQ）。 */
    private RecipeGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<RecipeGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        McItem target = catalog.randomTarget(random);
        McRecipe recipe = catalog.recipeOf(target.id()).orElseThrow();
        RecipeGame game = new RecipeGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(),
                target.id(), recipe.grid(),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    /** 查看场景：取该群最近一局（含刚结束），从未有过对局时才开新局。 */
    private RecipeGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    /** 多个候选时的取舍：优先该格的原料，其次数据集中的第一个。 */
    private McItem pickCandidate(List<McItem> candidates, RecipeGame game, int cell) {
        String slotItem = game.getGrid().get(cell - 1);
        if (slotItem != null) {
            for (McItem candidate : candidates) {
                if (candidate.id().equals(slotItem)) {
                    return candidate;
                }
            }
        }
        return candidates.getFirst();
    }

    private String emptyStreakSuffix(RecipeGame game) {
        if (game.hintAvailable()) {
            return "。\n已连续空猜 " + game.getEmptyStreak() + " 次，发送 /猜合成提示 可获得一次提示！";
        }
        return "（连续空猜 " + game.getEmptyStreak() + "/" + RecipeGame.HINT_EMPTY_STREAK + " 解锁提示）。";
    }

    private List<McguessSupport.Participant> participants(RecipeGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (RecipeGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(RecipeGame game) {
        List<RecipeGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            RecipeGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("cell", guess.cell());
            row.put("zh", guess.matchedZh());
            row.put("qq", guess.qq());
            row.put("icon", guess.matchedId() == null ? null : icons.dataUri(guess.matchedId()));
            switch (guess.result()) {
                case RecipeGuess.RESULT_HIT -> {
                    row.put("cls", "found");
                    row.put("label", "命中 " + guess.cell() + " 格");
                }
                case RecipeGuess.RESULT_EMPTY -> {
                    row.put("cls", "miss");
                    row.put("label", "空位");
                }
                default -> {
                    row.put("cls", "miss");
                    row.put("label", "不对");
                }
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
