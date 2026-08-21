package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.MobGame;
import online.yudream.base.plugin.mcguess.domain.MobGame.MobGuess;
import online.yudream.base.plugin.mcguess.domain.MobGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McguessDocumentMobGameRepository implements MobGameRepository {

    private static final String COLLECTION = "mcguess_mob_games";

    private final PluginDocumentStore store;

    public McguessDocumentMobGameRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<MobGame> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toGame);
    }

    @Override
    public Optional<MobGame> findActive(String connectionId, String channelId) {
        String channelKey = MobGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .filter(MobGame::isPlaying)
                .findFirst();
    }

    @Override
    public Optional<MobGame> findLatest(String connectionId, String channelId) {
        String channelKey = MobGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .max(Comparator.comparingLong(MobGame::getStartedAt));
    }

    @Override
    public List<MobGame> search(String status, int page, int size) {
        List<MobGame> all = collect(status);
        all.sort(Comparator.comparingLong(MobGame::getStartedAt).reversed());
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        return new ArrayList<>(all.subList(from, Math.min(from + size, all.size())));
    }

    @Override
    public long count(String status) {
        if (status == null || status.isBlank()) {
            return store.count(COLLECTION);
        }
        return collect(status).size();
    }

    @Override
    public long countAll() {
        return store.count(COLLECTION);
    }

    private List<MobGame> collect(String status) {
        List<Map<String, Object>> docs = status == null || status.isBlank()
                ? DocumentSupport.scanAll(store, COLLECTION)
                : DocumentSupport.scanByField(store, COLLECTION, "status", status);
        List<MobGame> all = new ArrayList<>();
        docs.forEach(doc -> all.add(toGame(doc)));
        return all;
    }

    @Override
    public void save(MobGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "mob");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("rowConds", new ArrayList<>(game.getRowConds()));
        doc.put("colConds", new ArrayList<>(game.getColConds()));
        doc.put("solution", new ArrayList<>(game.getSolution()));
        List<String> cellDocs = new ArrayList<>();
        for (String cell : game.getCells()) {
            cellDocs.add(cell == null ? "" : cell);
        }
        doc.put("cells", cellDocs);
        doc.put("hearts", game.getHearts());
        doc.put("status", game.getStatus());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (MobGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("cell", guess.cell());
            guessDoc.put("input", guess.input());
            guessDoc.put("mobId", guess.mobId());
            guessDoc.put("mobZh", guess.mobZh());
            guessDoc.put("result", guess.result());
            guessDoc.put("qq", guess.qq());
            guessDoc.put("userId", guess.userId());
            guessDoc.put("at", guess.at());
            DocumentSupport.stripNulls(guessDoc);
            guessDocs.add(guessDoc);
        }
        doc.put("guesses", guessDocs);
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, game.getId(), doc);
    }

    @Override
    public void delete(String id) {
        store.delete(COLLECTION, id);
    }

    private MobGame toGame(Map<String, Object> doc) {
        MobGame game = new MobGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                DocumentSupport.stringList(doc, "rowConds"),
                DocumentSupport.stringList(doc, "colConds"),
                DocumentSupport.stringList(doc, "solution"),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        List<String> cells = new ArrayList<>();
        for (String cell : DocumentSupport.stringList(doc, "cells")) {
            cells.add(cell.isEmpty() ? null : cell);
        }
        while (cells.size() < MobGame.CELL_COUNT) {
            cells.add(null);
        }
        game.restoreCells(cells);
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new MobGuess(
                    DocumentSupport.integer(guessDoc, "cell", 0),
                    DocumentSupport.string(guessDoc, "input"),
                    DocumentSupport.string(guessDoc, "mobId"),
                    DocumentSupport.string(guessDoc, "mobZh"),
                    DocumentSupport.string(guessDoc, "result"),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        game.restoreHearts(DocumentSupport.integer(doc, "hearts", MobGame.MAX_HEARTS));
        String status = DocumentSupport.stringOrDefault(doc, "status", MobGame.STATUS_PLAYING);
        if (MobGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (MobGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
