package online.yudream.base.plugin.pony.infrastructure.repository;

import online.yudream.base.plugin.pony.domain.HorsePlacement;
import online.yudream.base.plugin.pony.domain.PonyGame;
import online.yudream.base.plugin.pony.domain.PonyGameRepository;
import online.yudream.base.plugin.pony.domain.PonyPuzzle;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PonyDocumentGameRepository implements PonyGameRepository {

    private static final String COLLECTION = "pony_games";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public PonyDocumentGameRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<PonyGame> findActive(String connectionId, String channelId) {
        String channelKey = PonyGame.channelKey(connectionId, channelId);
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findByField(COLLECTION, "channelKey", channelKey, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> doc : docs) {
                PonyGame game = toGame(doc);
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
    public Optional<PonyGame> findLatest(String connectionId, String channelId) {
        String channelKey = PonyGame.channelKey(connectionId, channelId);
        PonyGame latest = null;
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findByField(COLLECTION, "channelKey", channelKey, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> doc : docs) {
                PonyGame game = toGame(doc);
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
    public List<PonyGame> search(String status, int page, int size) {
        List<PonyGame> all = new ArrayList<>();
        collect(status, all);
        all.sort(Comparator.comparingLong(PonyGame::getStartedAt).reversed());
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public long count(String status) {
        if (status == null || status.isBlank()) {
            return store.count(COLLECTION);
        }
        List<PonyGame> all = new ArrayList<>();
        collect(status, all);
        return all.size();
    }

    @Override
    public long countAll() {
        return store.count(COLLECTION);
    }

    private void collect(String status, List<PonyGame> target) {
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

    @Override
    public void save(PonyGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.channelKey());
        doc.put("size", game.getSize());
        doc.put("regions", new ArrayList<>(game.getRegions()));
        doc.put("solution", new ArrayList<>(game.getSolution()));
        doc.put("status", game.getStatus());
        doc.put("lives", game.getLives());
        doc.put("mistakes", game.getMistakes());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        doc.put("marks", new ArrayList<>(game.getMarks()));
        List<Map<String, Object>> horseDocs = new ArrayList<>();
        for (HorsePlacement horse : game.getHorses()) {
            Map<String, Object> horseDoc = new HashMap<>();
            horseDoc.put("cell", horse.cell());
            horseDoc.put("qq", horse.qq());
            horseDoc.put("userId", horse.userId());
            horseDoc.put("at", horse.at());
            DocumentSupport.stripNulls(horseDoc);
            horseDocs.add(horseDoc);
        }
        doc.put("horses", horseDocs);
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, game.getId(), doc);
    }

    @Override
    public void delete(String id) {
        store.delete(COLLECTION, id);
    }

    private PonyGame toGame(Map<String, Object> doc) {
        int size = DocumentSupport.integer(doc, "size", 8);
        int[] regions = DocumentSupport.intList(doc, "regions").stream().mapToInt(Integer::intValue).toArray();
        int[] solution = DocumentSupport.intList(doc, "solution").stream().mapToInt(Integer::intValue).toArray();
        PonyGame game = new PonyGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                new PonyPuzzle(size, regions, solution),
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        game.setLives(DocumentSupport.integer(doc, "lives", PonyGame.MAX_LIVES));
        game.setMistakes(DocumentSupport.integer(doc, "mistakes", 0));
        for (int mark : DocumentSupport.intList(doc, "marks")) {
            int row = mark / size;
            int col = mark % size;
            game.toggleMark(row, col);
        }
        for (Map<String, Object> horseDoc : DocumentSupport.mapList(doc, "horses")) {
            game.getHorses().add(new HorsePlacement(
                    DocumentSupport.integer(horseDoc, "cell", -1),
                    DocumentSupport.string(horseDoc, "qq"),
                    DocumentSupport.string(horseDoc, "userId"),
                    DocumentSupport.longValue(horseDoc, "at", 0)));
        }
        String status = DocumentSupport.stringOrDefault(doc, "status", PonyGame.STATUS_PLAYING);
        if (PonyGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (PonyGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
