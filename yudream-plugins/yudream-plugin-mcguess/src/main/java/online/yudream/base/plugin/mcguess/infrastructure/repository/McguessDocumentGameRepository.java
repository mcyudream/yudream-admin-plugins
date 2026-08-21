package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.McguessGame;
import online.yudream.base.plugin.mcguess.domain.McguessGame.McGuess;
import online.yudream.base.plugin.mcguess.domain.McguessGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McguessDocumentGameRepository implements McguessGameRepository {

    private static final String COLLECTION = "mcguess_games";

    private final PluginDocumentStore store;

    public McguessDocumentGameRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<McguessGame> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toGame);
    }

    @Override
    public Optional<McguessGame> findActive(String connectionId, String channelId) {
        String channelKey = McguessGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .filter(McguessGame::isPlaying)
                .findFirst();
    }

    @Override
    public Optional<McguessGame> findLatest(String connectionId, String channelId) {
        String channelKey = McguessGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .max(Comparator.comparingLong(McguessGame::getStartedAt));
    }

    @Override
    public List<McguessGame> search(String status, int page, int size) {
        List<McguessGame> all = collect(status);
        all.sort(Comparator.comparingLong(McguessGame::getStartedAt).reversed());
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

    private List<McguessGame> collect(String status) {
        List<Map<String, Object>> docs = status == null || status.isBlank()
                ? DocumentSupport.scanAll(store, COLLECTION)
                : DocumentSupport.scanByField(store, COLLECTION, "status", status);
        List<McguessGame> all = new ArrayList<>();
        docs.forEach(doc -> all.add(toGame(doc)));
        return all;
    }

    @Override
    public void save(McguessGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "item");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("targetId", game.getTargetId());
        doc.put("status", game.getStatus());
        doc.put("emptyStreak", game.getEmptyStreak());
        doc.put("hintsUsed", game.getHintsUsed());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        doc.put("revealed", new ArrayList<>(game.getRevealed()));
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (McGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("input", guess.input());
            guessDoc.put("matchedId", guess.matchedId());
            guessDoc.put("matchedZh", guess.matchedZh());
            guessDoc.put("result", guess.result());
            guessDoc.put("distance", guess.distance());
            guessDoc.put("occurrences", guess.occurrences());
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

    private McguessGame toGame(Map<String, Object> doc) {
        McguessGame game = new McguessGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                DocumentSupport.string(doc, "targetId"),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        for (String itemId : DocumentSupport.stringList(doc, "revealed")) {
            game.reveal(itemId);
        }
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new McGuess(
                    DocumentSupport.string(guessDoc, "input"),
                    DocumentSupport.string(guessDoc, "matchedId"),
                    DocumentSupport.string(guessDoc, "matchedZh"),
                    DocumentSupport.string(guessDoc, "result"),
                    guessDoc.containsKey("distance") ? DocumentSupport.integer(guessDoc, "distance", 0) : null,
                    DocumentSupport.integer(guessDoc, "occurrences", 0),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        game.restoreCounters(DocumentSupport.integer(doc, "emptyStreak", 0),
                DocumentSupport.integer(doc, "hintsUsed", 0));
        String status = DocumentSupport.stringOrDefault(doc, "status", McguessGame.STATUS_PLAYING);
        if (McguessGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (McguessGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
