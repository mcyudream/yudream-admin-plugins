package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.domain.SpotGame;
import online.yudream.base.plugin.mcguess.domain.SpotGame.SpotGuess;
import online.yudream.base.plugin.mcguess.domain.SpotGameRepository;
import online.yudream.base.plugin.mcguess.domain.SpotGenerator;
import online.yudream.base.plugin.mcguess.domain.SpotGenerator.SpotPuzzle;
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
 * 配方找茬用例编排（群回合制）：展示一个真实 3x3 配方，但某一非空格被替换成了
 * 违和的物品（优先同族变体），第一个指出错误格（1-9）的人获胜。
 */
public class SpotAppService {

    private static final int RECORD_LIMIT = 10;

    private final SpotGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final SpotGenerator generator;
    private final Random random = new Random();

    public SpotAppService(SpotGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
        this.generator = new SpotGenerator(catalog);
    }

    // ---------------------------------------------------------------- 群聊指令用例

    /** /找茬 <1-9>：指出错误格；无参数时查看当前局面。 */
    public String answer(PluginEvent event, Long userId, String cellInput) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与找茬。";
        }
        if (cellInput == null || cellInput.isBlank()) {
            return status(event);
        }
        int cell;
        try {
            cell = Integer.parseInt(cellInput.trim());
        } catch (NumberFormatException e) {
            return "格子只认 1-9 的数字，例如 /找茬 5。";
        }
        if (!SpotGame.isValidCell(cell)) {
            return "格子只认 1-9 的数字，例如 /找茬 5。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            SpotGame game = activeGame(event, userIdString);
            if (!game.isPlaying()) {
                return "本局找茬已结束，错误格是 " + game.getWrongCell() + " 号格（应为「" + zhOf(game.getCorrectId())
                        + "」）。发送 /找茬 开始新一局！";
            }
            long now = System.currentTimeMillis();
            if (game.isWrongCell(cell)) {
                game.addGuess(new SpotGuess(cell, SpotGuess.RESULT_WIN, event.userId(), userIdString, now));
                game.win(event.userId(), userIdString, now);
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                support.recordGameEnd(McguessMode.SPOT, participants(game), userIdString);
                List<String> added = support.collect(userIdString, event.userId(), List.of(game.getTargetId()));
                return "🎉 火眼金睛！QQ " + event.userId() + " 指出了 " + cell + " 号格的错误："
                        + "「" + zhOf(game.getGrid().get(cell - 1)) + "」应为「" + zhOf(game.getCorrectId()) + "」！"
                        + collectionSuffix(added)
                        + "\n发送 /找茬 立即开始新一局！";
            }
            game.addGuess(new SpotGuess(cell, SpotGuess.RESULT_MISS, event.userId(), userIdString, now));
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            return "❌ " + cell + " 号格的「" + zhOf(game.getGrid().get(cell - 1)) + "」没有问题，再仔细看看！";
        }
    }

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            SpotGame game = latestGame(event);
            if (!game.isPlaying()) {
                return "🏁 上一局找茬已结束，错误格是 " + game.getWrongCell() + " 号格（应为「"
                        + zhOf(game.getCorrectId()) + "」）。发送 /找茬 开始新一局！";
            }
            return "🔍 MC 配方找茬进行中：「" + zhOf(game.getTargetId()) + "」的合成配方有一格被掉包了，"
                    + "已指认 " + game.getGuesses().size() + " 次。"
                    + "\n发送 /找茬 <1-9> 指出错误格，/结束找茬 投降揭晓。";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<SpotGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的找茬对局，发送 /找茬 开始新一局！";
            }
            SpotGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.SPOT, participants(game), null);
            return "🏳️ 本局找茬已结束：「" + zhOf(game.getTargetId()) + "」配方的错误格是 "
                    + game.getWrongCell() + " 号格，「" + zhOf(game.getGrid().get(game.getWrongCell() - 1))
                    + "」应为「" + zhOf(game.getCorrectId()) + "」。发送 /找茬 开始新一局！";
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            SpotGame game = latestGame(event);
            boolean won = SpotGame.STATUS_WON.equals(game.getStatus());
            boolean lost = SpotGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;

            List<Map<String, Object>> cellRows = new ArrayList<>();
            List<String> grid = game.getGrid();
            for (int i = 0; i < grid.size(); i++) {
                Map<String, Object> cellRow = new HashMap<>();
                String itemId = grid.get(i);
                cellRow.put("cell", i + 1);
                cellRow.put("empty", itemId == null);
                cellRow.put("zh", itemId == null ? "" : zhOf(itemId));
                cellRow.put("icon", itemId == null ? null : icons.dataUri(itemId));
                cellRow.put("wrong", ended && game.isWrongCell(i + 1));
                cellRow.put("correctZh", ended && game.isWrongCell(i + 1) ? zhOf(game.getCorrectId()) : null);
                cellRow.put("correctIcon", ended && game.isWrongCell(i + 1) ? icons.dataUri(game.getCorrectId()) : null);
                cellRows.add(cellRow);
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 配方找茬");
            variables.put("subtitle", "配方有一格被掉包 · 指出错误格 · JE 1.20.5");
            variables.put("targetZh", zhOf(game.getTargetId()));
            variables.put("targetIcon", icons.dataUri(game.getTargetId()));
            variables.put("cells", cellRows);
            variables.put("wrongCell", ended ? game.getWrongCell() : null);
            variables.put("correctZh", ended ? zhOf(game.getCorrectId()) : null);
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

    private SpotGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<SpotGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        SpotPuzzle puzzle = generator.generate(random);
        SpotGame game = new SpotGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(),
                puzzle.targetId(), puzzle.grid(), puzzle.wrongCell(), puzzle.correctId(),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    private SpotGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    private List<McguessSupport.Participant> participants(SpotGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (SpotGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(SpotGame game) {
        List<SpotGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            SpotGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("qq", guess.qq());
            row.put("label", "指认 " + guess.cell() + " 号格");
            row.put("cls", SpotGuess.RESULT_WIN.equals(guess.result()) ? "found" : "miss");
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
