package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.SpotGame;
import online.yudream.base.plugin.mcguess.domain.SpotGame.SpotGuess;
import online.yudream.base.plugin.mcguess.domain.SpotGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class McguessDocumentSpotGameRepository extends DocumentChannelGameRepository<SpotGame> implements SpotGameRepository {

    private static final String COLLECTION = "mcguess_spot_games";

    public McguessDocumentSpotGameRepository(PluginDocumentStore store) {
        super(store, COLLECTION);
    }

    @Override
    protected Map<String, Object> toDoc(SpotGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "spot");
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
        doc.put("wrongCell", game.getWrongCell());
        doc.put("correctId", game.getCorrectId());
        doc.put("status", game.getStatus());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (SpotGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("cell", guess.cell());
            guessDoc.put("result", guess.result());
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
    protected SpotGame toGame(Map<String, Object> doc) {
        List<String> grid = new ArrayList<>();
        for (String slot : DocumentSupport.stringList(doc, "grid")) {
            grid.add(slot.isEmpty() ? null : slot);
        }
        while (grid.size() < 9) {
            grid.add(null);
        }
        SpotGame game = new SpotGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                DocumentSupport.string(doc, "targetId"),
                grid,
                DocumentSupport.integer(doc, "wrongCell", 0),
                DocumentSupport.string(doc, "correctId"),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new SpotGuess(
                    DocumentSupport.integer(guessDoc, "cell", 0),
                    DocumentSupport.string(guessDoc, "result"),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        String status = DocumentSupport.stringOrDefault(doc, "status", SpotGame.STATUS_PLAYING);
        if (SpotGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (SpotGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
