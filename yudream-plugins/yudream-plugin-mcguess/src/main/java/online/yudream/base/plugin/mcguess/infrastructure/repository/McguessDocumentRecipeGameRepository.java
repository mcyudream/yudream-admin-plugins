package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.RecipeGame;
import online.yudream.base.plugin.mcguess.domain.RecipeGame.RecipeGuess;
import online.yudream.base.plugin.mcguess.domain.RecipeGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McguessDocumentRecipeGameRepository implements RecipeGameRepository {

    private static final String COLLECTION = "mcguess_recipe_games";

    private final PluginDocumentStore store;

    public McguessDocumentRecipeGameRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<RecipeGame> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toGame);
    }

    @Override
    public Optional<RecipeGame> findActive(String connectionId, String channelId) {
        String channelKey = RecipeGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .filter(RecipeGame::isPlaying)
                .findFirst();
    }

    @Override
    public Optional<RecipeGame> findLatest(String connectionId, String channelId) {
        String channelKey = RecipeGame.channelKey(connectionId, channelId);
        return DocumentSupport.scanByField(store, COLLECTION, "channelKey", channelKey).stream()
                .map(this::toGame)
                .max(Comparator.comparingLong(RecipeGame::getStartedAt));
    }

    @Override
    public List<RecipeGame> search(String status, int page, int size) {
        List<RecipeGame> all = collect(status);
        all.sort(Comparator.comparingLong(RecipeGame::getStartedAt).reversed());
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

    private List<RecipeGame> collect(String status) {
        List<Map<String, Object>> docs = status == null || status.isBlank()
                ? DocumentSupport.scanAll(store, COLLECTION)
                : DocumentSupport.scanByField(store, COLLECTION, "status", status);
        List<RecipeGame> all = new ArrayList<>();
        docs.forEach(doc -> all.add(toGame(doc)));
        return all;
    }

    @Override
    public void save(RecipeGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "recipe");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("targetId", game.getTargetId());
        List<String> gridDocs = new ArrayList<>();
        for (String slot : game.getGrid()) {
            gridDocs.add(slot == null ? "" : slot);
        }
        doc.put("grid", gridDocs);
        doc.put("revealed", new ArrayList<>(game.getRevealed()));
        doc.put("status", game.getStatus());
        doc.put("emptyStreak", game.getEmptyStreak());
        doc.put("hintsUsed", game.getHintsUsed());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (RecipeGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("cell", guess.cell());
            guessDoc.put("input", guess.input());
            guessDoc.put("matchedId", guess.matchedId());
            guessDoc.put("matchedZh", guess.matchedZh());
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

    private RecipeGame toGame(Map<String, Object> doc) {
        List<String> grid = new ArrayList<>();
        for (String slot : DocumentSupport.stringList(doc, "grid")) {
            grid.add(slot.isEmpty() ? null : slot);
        }
        while (grid.size() < 9) {
            grid.add(null);
        }
        RecipeGame game = new RecipeGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                DocumentSupport.string(doc, "targetId"),
                grid,
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        for (String itemId : DocumentSupport.stringList(doc, "revealed")) {
            game.revealItem(itemId);
        }
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new RecipeGuess(
                    DocumentSupport.integer(guessDoc, "cell", 0),
                    DocumentSupport.string(guessDoc, "input"),
                    DocumentSupport.string(guessDoc, "matchedId"),
                    DocumentSupport.string(guessDoc, "matchedZh"),
                    DocumentSupport.string(guessDoc, "result"),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        game.restoreCounters(DocumentSupport.integer(doc, "emptyStreak", 0),
                DocumentSupport.integer(doc, "hintsUsed", 0));
        String status = DocumentSupport.stringOrDefault(doc, "status", RecipeGame.STATUS_PLAYING);
        if (RecipeGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (RecipeGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
