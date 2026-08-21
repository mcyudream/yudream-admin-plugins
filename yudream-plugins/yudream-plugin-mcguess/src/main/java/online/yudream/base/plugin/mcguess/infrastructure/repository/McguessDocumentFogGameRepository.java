package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.FogGame;
import online.yudream.base.plugin.mcguess.domain.FogGame.FogGuess;
import online.yudream.base.plugin.mcguess.domain.FogGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class McguessDocumentFogGameRepository extends DocumentChannelGameRepository<FogGame> implements FogGameRepository {

    private static final String COLLECTION = "mcguess_fog_games";

    public McguessDocumentFogGameRepository(PluginDocumentStore store) {
        super(store, COLLECTION);
    }

    @Override
    protected Map<String, Object> toDoc(FogGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "fog");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("targetId", game.getTargetId());
        doc.put("stage", game.getStage());
        doc.put("status", game.getStatus());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (FogGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
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
        return doc;
    }

    @Override
    protected FogGame toGame(Map<String, Object> doc) {
        FogGame game = new FogGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                DocumentSupport.string(doc, "targetId"),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        game.restoreStage(DocumentSupport.integer(doc, "stage", 0));
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new FogGuess(
                    DocumentSupport.string(guessDoc, "input"),
                    DocumentSupport.string(guessDoc, "matchedId"),
                    DocumentSupport.string(guessDoc, "matchedZh"),
                    DocumentSupport.string(guessDoc, "result"),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        String status = DocumentSupport.stringOrDefault(doc, "status", FogGame.STATUS_PLAYING);
        if (FogGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (FogGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
