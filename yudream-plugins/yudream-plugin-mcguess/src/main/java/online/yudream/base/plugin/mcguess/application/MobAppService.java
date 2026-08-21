package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McMobCatalog;
import online.yudream.base.plugin.mcguess.domain.MobBoardGenerator;
import online.yudream.base.plugin.mcguess.domain.MobGame;
import online.yudream.base.plugin.mcguess.domain.MobGame.MobGuess;
import online.yudream.base.plugin.mcguess.domain.MobGameRepository;
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

/**
 * 猜生物用例编排（图中填格子）：3 行条件 × 3 列条件的 9 格棋盘，
 * 生物须同时满足行与列条件且同盘不重复；填错扣 1 心（共 6 心），
 * 填满 9 格获胜，心耗尽或 /结束猜生物 失败并揭晓参考答案。
 */
public class MobAppService {

    /** 棋盘上展示的最近填格记录条数。 */
    private static final int RECORD_LIMIT = 10;

    private final MobGameRepository games;
    private final McMobCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final MobBoardGenerator generator;
    private final Random random = new Random();

    public MobAppService(MobGameRepository games, McMobCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
        this.generator = new MobBoardGenerator(catalog);
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            MobGame game = latestGame(event);
            // 裸指令在上一局结束后直接开新局（终局参考答案已在结束消息中揭晓）
            boolean restarted = !game.isPlaying();
            if (restarted) {
                game = activeGame(event, null);
            }
            return (restarted ? "🎬 新一局开始！\n" : "")
                    + "🧬 MC 猜生物进行中：已填 " + game.filledCount() + "/9 格，剩余 ❤️ ×" + game.getHearts() + "。"
                    + "\n发送 /猜生物 <1-9> <生物名> 填格，/结束猜生物 投降揭晓。";
        }
    }

    public String fill(PluginEvent event, Long userId, int cell, String mobName) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与猜生物。";
        }
        if (!MobGame.isValidCell(cell)) {
            return "格子编号需要在 1-9 之间（3x3 棋盘从左到右、从上到下）。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            MobGame game = activeGame(event, userIdString);
            Optional<McMobCatalog.McMob> matched = catalog.match(mobName);
            if (matched.isEmpty()) {
                return "没有找到名为「" + mobName.trim() + "」的 Minecraft 生物（JE 1.20.5），检查下名字？" ;
            }
            McMobCatalog.McMob mob = matched.get();
            if (game.isFilled(cell)) {
                return "第 " + cell + " 格已经填了「" + mobZh(game.getCells().get(cell - 1)) + "」，换个空格试试。";
            }
            if (game.hasUsed(mob.id())) {
                return "「" + mob.zh() + "」本局已经用过了：同一张棋盘里答案不能重复。";
            }
            long now = System.currentTimeMillis();
            if (game.satisfies(cell, mob)) {
                game.fill(cell, mob.id());
                game.addGuess(new MobGuess(cell, mobName.trim(), mob.id(), mob.zh(),
                        MobGuess.RESULT_FILLED, event.userId(), userIdString, now));
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                if (game.isComplete()) {
                    game.win(event.userId(), userIdString, System.currentTimeMillis());
                    games.save(game);
                    support.recordGameEnd(McguessMode.MOB, participants(game), userIdString);
                    return "🎉 恭喜 QQ " + event.userId() + " 填入「" + mob.zh() + "」，9 格全部填完，本群获胜！"
                            + "\n剩余 ❤️ ×" + game.getHearts() + "，发送 /猜生物 开始新一局！";
                }
                return "✅ 「" + mob.zh() + "」填入第 " + cell + " 格正确！（已填 " + game.filledCount()
                        + "/9 格，剩余 ❤️ ×" + game.getHearts() + "）";
            }
            game.loseHeart();
            game.addGuess(new MobGuess(cell, mobName.trim(), mob.id(), mob.zh(),
                    MobGuess.RESULT_WRONG, event.userId(), userIdString, now));
            String rowZh = condZh(game.getRowConds().get((cell - 1) / 3));
            String colZh = condZh(game.getColConds().get((cell - 1) % 3));
            if (game.getHearts() <= 0) {
                game.lose(System.currentTimeMillis());
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                support.recordGameEnd(McguessMode.MOB, participants(game), null);
                return "💔 「" + mob.zh() + "」不满足第 " + cell + " 格的条件（" + rowZh + " × " + colZh
                        + "），❤️ 耗尽，本局失败！棋盘已揭晓参考答案，发送 /猜生物 开始新一局。";
            }
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            return "❌ 「" + mob.zh() + "」不满足第 " + cell + " 格的条件（" + rowZh + " × " + colZh
                    + "），扣 1 ❤️（剩余 " + game.getHearts() + "/" + MobGame.MAX_HEARTS + "）。";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<MobGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的猜生物对局，发送 /猜生物 开始新一局！";
            }
            MobGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.MOB, participants(game), null);
            return "🏳️ 本局猜生物已结束并揭晓参考答案（已填 " + game.filledCount() + "/9 格）。发送 /猜生物 开始新一局！";
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    /**
     * 本群当前对局的棋盘渲染变量快照；不在群聊中时返回 null。
     */
    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            MobGame game = latestGame(event);
            boolean won = MobGame.STATUS_WON.equals(game.getStatus());
            boolean lost = MobGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 猜生物");
            variables.put("subtitle", "填格子 · 同盘不重复 · JE 1.20.5");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String code : game.getRowConds()) {
                rows.add(Map.of("zh", condZh(code)));
            }
            variables.put("rows", rows);
            List<Map<String, Object>> cols = new ArrayList<>();
            for (String code : game.getColConds()) {
                cols.add(Map.of("zh", condZh(code)));
            }
            variables.put("cols", cols);
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < MobGame.CELL_COUNT; i++) {
                Map<String, Object> cell = new HashMap<>();
                cell.put("index", i + 1);
                String filled = game.getCells().get(i);
                if (filled != null) {
                    cell.put("state", "filled");
                    cell.put("zh", mobZh(filled));
                    cell.put("icon", icons.dataUri(catalog.eggIconId(filled)));
                } else if (lost) {
                    String solution = game.getSolution().get(i);
                    cell.put("state", "solution");
                    cell.put("zh", mobZh(solution));
                    cell.put("icon", icons.dataUri(catalog.eggIconId(solution)));
                } else {
                    cell.put("state", "empty");
                }
                cells.add(cell);
            }
            variables.put("cells", cells);
            variables.put("hearts", game.getHearts());
            variables.put("heartsMax", MobGame.MAX_HEARTS);
            variables.put("filled", game.filledCount());
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

    /** 本群当前进行中的对局；不存在时生成新棋盘开新局（startedByUserId 为空时仅记开局 QQ）。 */
    private MobGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<MobGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        MobBoardGenerator.MobBoard board = generator.generate(random);
        MobGame game = new MobGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(),
                board.rows(), board.cols(), board.solution(),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    /** 查看场景：取该群最近一局（含刚结束），从未有过对局时才开新局。 */
    private MobGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    private List<McguessSupport.Participant> participants(MobGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (MobGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(MobGame game) {
        List<MobGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            MobGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("cell", guess.cell());
            row.put("zh", guess.mobZh());
            row.put("qq", guess.qq());
            row.put("icon", guess.mobId() == null ? null : icons.dataUri(catalog.eggIconId(guess.mobId())));
            boolean filled = MobGuess.RESULT_FILLED.equals(guess.result());
            row.put("cls", filled ? "found" : "miss");
            row.put("label", filled ? "填入 " + guess.cell() + " 格" : "扣 ❤️");
            rows.add(row);
        }
        return rows;
    }

    private String mobZh(String mobId) {
        return catalog.byId(mobId).map(McMobCatalog.McMob::zh).orElse(mobId);
    }

    private String condZh(String code) {
        return catalog.condition(code).map(McMobCatalog.McCondition::zh).orElse(code);
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }
}
