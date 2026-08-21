package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.BingoGame;
import online.yudream.base.plugin.mcguess.domain.BingoGame.BingoGuess;
import online.yudream.base.plugin.mcguess.domain.BingoGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MC 宾果用例编排（群回合制）：5x5 共享棋盘 25 格不同物品，格子只显示图标与序号，
 * 玩家看图报物品名点亮格子（智能匹配），率先点亮任意一整行 / 整列 / 对角线者获胜；
 * 格名在点亮后揭晓，本局结束时全部揭晓。
 */
public class BingoAppService {

    private static final int RECORD_LIMIT = 10;

    private final BingoGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final Random random = new Random();

    public BingoAppService(BingoGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    public String claim(PluginEvent event, Long userId, String rawInput) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与宾果。";
        }
        if (rawInput == null || rawInput.isBlank()) {
            return status(event);
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            BingoGame game = activeGame(event, userIdString);
            List<McItem> candidates = catalog.match(rawInput);
            if (candidates.isEmpty()) {
                return "没有找到 1.20.5 中名为「" + rawInput.trim() + "」的物品。"
                        + "\n支持智能匹配：可忽略颜色词（红色/白色…）、主世界木质词（橡木/云杉…）与材质词（染色/磨制/切制）。";
            }
            McItem matched = pickCandidate(candidates, game);
            int cell = game.cellIndexOf(matched.id());
            long now = System.currentTimeMillis();
            if (cell < 0) {
                game.addGuess(new BingoGuess(rawInput.trim(), matched.id(), matched.zh(),
                        BingoGuess.RESULT_MISS, null, event.userId(), userIdString, now));
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                return "❌ 「" + matched.zh() + "」不在本局宾果棋盘上。";
            }
            if (game.isClaimed(cell)) {
                game.addGuess(new BingoGuess(rawInput.trim(), matched.id(), matched.zh(),
                        BingoGuess.RESULT_DUP, cell, event.userId(), userIdString, now));
                games.save(game);
                return "「" + matched.zh() + "」所在格子（" + cell + " 号格）已被 QQ "
                        + game.claimerQqOf(cell) + " 点亮过了，换一格试试。";
            }
            game.claim(cell, event.userId(), userIdString);
            game.addGuess(new BingoGuess(rawInput.trim(), matched.id(), matched.zh(),
                    BingoGuess.RESULT_CLAIM, cell, event.userId(), userIdString, now));
            List<Integer> line = game.findCompletedLine();
            if (line != null) {
                game.win(event.userId(), userIdString, line, now);
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                support.recordGameEnd(McguessMode.BINGO, participants(game), userIdString);
                List<String> added = support.collect(userIdString, event.userId(), List.of(matched.id()));
                return "🎉 QQ " + event.userId() + " 点亮「" + matched.zh() + "」（" + cell + " 号格），"
                        + "BINGO！率先连成一线，本局获胜！"
                        + collectionSuffix(added)
                        + "\n本局共点亮 " + game.claimedCount() + "/25 格。发送 /宾果 开始新一局！";
            }
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            List<String> added = support.collect(userIdString, event.userId(), List.of(matched.id()));
            return "✨ QQ " + event.userId() + " 点亮了「" + matched.zh() + "」（" + cell + " 号格），"
                    + "当前已点亮 " + game.claimedCount() + "/25 格。" + collectionSuffix(added);
        }
    }

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            BingoGame game = latestGame(event);
            // 裸指令在上一局结束后直接开新局（终局结果已在结束消息中公布）
            boolean restarted = !game.isPlaying();
            if (restarted) {
                game = activeGame(event, null);
            }
            return (restarted ? "🎬 新一局开始！\n" : "")
                    + "🎱 MC 宾果进行中：已点亮 " + game.claimedCount() + "/25 格。"
                    + "\n格子只显示图标，看图标发送 /宾果 <物品名> 点亮格子，任意一整行 / 整列 / 对角线连成即胜；/结束宾果 投降。";
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<BingoGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的宾果对局，发送 /宾果 开始新一局！";
            }
            BingoGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.BINGO, participants(game), null);
            return "🏳️ 本局宾果已结束（共点亮 " + game.claimedCount() + "/25 格）。发送 /宾果 开始新一局！";
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            BingoGame game = latestGame(event);
            boolean won = BingoGame.STATUS_WON.equals(game.getStatus());
            boolean lost = BingoGame.STATUS_LOST.equals(game.getStatus());

            List<Map<String, Object>> cellRows = new ArrayList<>();
            List<String> cells = game.getCells();
            boolean revealAll = won || lost;
            for (int i = 0; i < cells.size(); i++) {
                int cellNo = i + 1;
                boolean claimed = game.isClaimed(cellNo);
                Map<String, Object> cellRow = new HashMap<>();
                cellRow.put("cell", cellNo);
                // 未点亮的格子隐藏物品名，玩家只能从图标辨认；点亮或本局结束后揭晓
                cellRow.put("zh", claimed || revealAll ? zhOf(cells.get(i)) : null);
                cellRow.put("icon", icons.dataUri(cells.get(i)));
                cellRow.put("claimed", claimed);
                cellRow.put("claimerQq", game.claimerQqOf(cellNo));
                cellRow.put("win", game.getWinCells().contains(i));
                cellRows.add(cellRow);
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 宾果");
            variables.put("subtitle", "看图标认物品点亮格子 · 连成一线即胜 · JE 1.20.5");
            variables.put("cells", cellRows);
            variables.put("claimedCount", game.claimedCount());
            variables.put("cellCount", BingoGame.CELL_COUNT);
            variables.put("won", won);
            variables.put("lost", lost);
            variables.put("ended", won || lost);
            variables.put("winnerQq", game.getWinnerQq());
            variables.put("records", recordRows(game));
            variables.put("banner", banner);
            return variables;
        }
    }

    // ---------------------------------------------------------------- 内部支撑

    private BingoGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<BingoGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        List<String> pool = new ArrayList<>(catalog.iconItems().stream().map(McItem::id).toList());
        Collections.shuffle(pool, random);
        BingoGame game = new BingoGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(),
                pool.subList(0, BingoGame.CELL_COUNT), event.userId(), startedByUserId,
                System.currentTimeMillis());
        games.save(game);
        return game;
    }

    private BingoGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    /** 多个候选时优先棋盘上未点亮的格子，其次已点亮的格子，最后数据集第一个。 */
    private McItem pickCandidate(List<McItem> candidates, BingoGame game) {
        for (McItem candidate : candidates) {
            int cell = game.cellIndexOf(candidate.id());
            if (cell > 0 && !game.isClaimed(cell)) {
                return candidate;
            }
        }
        for (McItem candidate : candidates) {
            if (game.cellIndexOf(candidate.id()) > 0) {
                return candidate;
            }
        }
        return candidates.getFirst();
    }

    private List<McguessSupport.Participant> participants(BingoGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (BingoGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(BingoGame game) {
        List<BingoGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            BingoGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("zh", guess.matchedZh());
            row.put("qq", guess.qq());
            row.put("icon", guess.matchedId() == null ? null : icons.dataUri(guess.matchedId()));
            if (BingoGuess.RESULT_CLAIM.equals(guess.result())) {
                row.put("cls", "found");
                row.put("label", "点亮 " + guess.cell() + " 号格");
            } else if (BingoGuess.RESULT_DUP.equals(guess.result())) {
                row.put("cls", "miss");
                row.put("label", "重复");
            } else {
                row.put("cls", "miss");
                row.put("label", "不在盘上");
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
