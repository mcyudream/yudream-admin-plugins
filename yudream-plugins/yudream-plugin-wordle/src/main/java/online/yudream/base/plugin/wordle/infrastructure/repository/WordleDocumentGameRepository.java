package online.yudream.base.plugin.wordle.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.wordle.domain.Guess;
import online.yudream.base.plugin.wordle.domain.LetterState;
import online.yudream.base.plugin.wordle.domain.WordleGame;
import online.yudream.base.plugin.wordle.domain.WordleGameRepository;
import online.yudream.base.plugin.wordle.domain.WordleMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WordleDocumentGameRepository implements WordleGameRepository {

    private static final String COLLECTION = "wordle_games";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public WordleDocumentGameRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<WordleGame> findActive(String connectionId, String channelId) {
        String channelKey = WordleGame.channelKey(connectionId, channelId);
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findByField(COLLECTION, "channelKey", channelKey, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> doc : docs) {
                WordleGame game = toGame(doc);
                if (game.isPlaying()) {
                    return Optional.of(game);
                }
            }
            if (docs.size() < SCAN_PAGE_SIZE) {
                return Optional.empty();
            }
            page++;
        }
    }

    @Override
    public Optional<WordleGame> findLatest(String connectionId, String channelId) {
        String channelKey = WordleGame.channelKey(connectionId, channelId);
        WordleGame latest = null;
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findByField(COLLECTION, "channelKey", channelKey, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> doc : docs) {
                WordleGame game = toGame(doc);
                if (latest == null || game.getStartedAt() > latest.getStartedAt()) {
                    latest = game;
                }
            }
            if (docs.size() < SCAN_PAGE_SIZE) {
                return Optional.ofNullable(latest);
            }
            page++;
        }
    }

    @Override
    public List<WordleGame> search(String status, int page, int size) {
        List<WordleGame> all = new ArrayList<>();
        collect(status, all);
        all.sort(Comparator.comparingLong(WordleGame::getStartedAt).reversed());
        return paginate(all, page, size);
    }

    @Override
    public long count(String status) {
        if (status == null || status.isBlank()) {
            return store.count(COLLECTION);
        }
        List<WordleGame> all = new ArrayList<>();
        collect(status, all);
        return all.size();
    }

    @Override
    public long countAll() {
        return store.count(COLLECTION);
    }

    private void collect(String status, List<WordleGame> target) {
        if (status == null || status.isBlank()) {
            int page = 1;
            while (true) {
                List<Map<String, Object>> docs = store.findAll(COLLECTION, page, SCAN_PAGE_SIZE);
                docs.forEach(doc -> target.add(toGame(doc)));
                if (docs.size() < SCAN_PAGE_SIZE) {
                    return;
                }
                page++;
            }
        }
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findByField(COLLECTION, "status", status, page, SCAN_PAGE_SIZE);
            docs.forEach(doc -> target.add(toGame(doc)));
            if (docs.size() < SCAN_PAGE_SIZE) {
                return;
            }
            page++;
        }
    }

    private static <T> List<T> paginate(List<T> all, int page, int size) {
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public void save(WordleGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.channelKey());
        doc.put("mode", game.getMode().name());
        doc.put("answer", game.getAnswer());
        doc.put("length", game.length());
        doc.put("hardMode", game.isHardMode());
        doc.put("status", game.getStatus());
        doc.put("maxGuesses", game.getMaxGuesses());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (Guess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("word", guess.word());
            guessDoc.put("states", guess.states().stream().map(LetterState::name).toList());
            guessDoc.put("userId", guess.userId());
            guessDoc.put("qq", guess.qq());
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

    private WordleGame toGame(Map<String, Object> doc) {
        WordleGame game = new WordleGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                WordleMode.from(DocumentSupport.stringOrDefault(doc, "mode", "ENGLISH")),
                DocumentSupport.string(doc, "answer"),
                DocumentSupport.bool(doc, "hardMode", false),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        String status = DocumentSupport.stringOrDefault(doc, "status", WordleGame.STATUS_PLAYING);
        if (WordleGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (WordleGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            List<LetterState> states = DocumentSupport.stringList(guessDoc, "states").stream()
                    .map(LetterState::valueOf)
                    .toList();
            game.addGuess(new Guess(
                    DocumentSupport.string(guessDoc, "word"),
                    states,
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        return game;
    }
}
