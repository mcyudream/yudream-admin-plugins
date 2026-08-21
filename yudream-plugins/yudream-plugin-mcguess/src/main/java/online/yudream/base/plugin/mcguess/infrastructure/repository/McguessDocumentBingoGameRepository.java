package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.BingoGame;
import online.yudream.base.plugin.mcguess.domain.BingoGame.BingoGuess;
import online.yudream.base.plugin.mcguess.domain.BingoGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class McguessDocumentBingoGameRepository extends DocumentChannelGameRepository<BingoGame> implements BingoGameRepository {

    private static final String COLLECTION = "mcguess_bingo_games";

    public McguessDocumentBingoGameRepository(PluginDocumentStore store) {
        super(store, COLLECTION);
    }

    @Override
    protected Map<String, Object> toDoc(BingoGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "bingo");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("cells", new ArrayList<>(game.getCells()));
        List<String> claimers = new ArrayList<>();
        List<String> claimerQqs = new ArrayList<>();
        for (int cell = 1; cell <= BingoGame.CELL_COUNT; cell++) {
            claimers.add(game.claimerOf(cell));
            claimerQqs.add(game.claimerQqOf(cell));
        }
        doc.put("claimers", claimers);
        doc.put("claimerQqs", claimerQqs);
        List<Integer> winCells = new ArrayList<>();
        for (int index : game.getWinCells()) {
            winCells.add(index);
        }
        doc.put("winCells", winCells);
        doc.put("status", game.getStatus());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (BingoGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("input", guess.input());
            guessDoc.put("matchedId", guess.matchedId());
            guessDoc.put("matchedZh", guess.matchedZh());
            guessDoc.put("result", guess.result());
            guessDoc.put("cell", guess.cell());
            guessDoc.put("qq", guess.qq());
            guessDoc.put("userId", guess.userId());
            guessDoc.put("at", guess.at());
            DocumentSupport.stripNulls(guessDoc);
            guessDocs.add(guessDoc);
        }
        doc.put("guesses", guessDocs);
        return doc;
    }

    @Override
    protected BingoGame toGame(Map<String, Object> doc) {
        List<String> cells = new ArrayList<>(DocumentSupport.stringList(doc, "cells"));
        while (cells.size() < BingoGame.CELL_COUNT) {
            cells.add("barrier");
        }
        BingoGame game = new BingoGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                cells,
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        List<String> claimers = DocumentSupport.stringList(doc, "claimers");
        List<String> claimerQqs = DocumentSupport.stringList(doc, "claimerQqs");
        for (int i = 0; i < claimers.size() && i < BingoGame.CELL_COUNT; i++) {
            String claimer = claimers.get(i);
            if (!claimer.isEmpty()) {
                String qq = i < claimerQqs.size() ? claimerQqs.get(i) : "";
                game.claim(i + 1, qq, claimer);
            }
        }
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            String cellValue = DocumentSupport.string(guessDoc, "cell");
            game.addGuess(new BingoGuess(
                    DocumentSupport.string(guessDoc, "input"),
                    DocumentSupport.string(guessDoc, "matchedId"),
                    DocumentSupport.string(guessDoc, "matchedZh"),
                    DocumentSupport.string(guessDoc, "result"),
                    cellValue == null || cellValue.isBlank() ? null : Integer.parseInt(cellValue),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        List<Integer> winCells = new ArrayList<>();
        for (String value : DocumentSupport.stringList(doc, "winCells")) {
            winCells.add(Integer.parseInt(value));
        }
        String status = DocumentSupport.stringOrDefault(doc, "status", BingoGame.STATUS_PLAYING);
        if (BingoGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    winCells, DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (BingoGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
