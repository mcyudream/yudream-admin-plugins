package online.yudream.base.plugin.pony.infrastructure.repository;

import online.yudream.base.plugin.pony.domain.PonyPlayer;
import online.yudream.base.plugin.pony.domain.PonyPlayerRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PonyDocumentPlayerRepository implements PonyPlayerRepository {

    private static final String COLLECTION = "pony_players";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public PonyDocumentPlayerRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<PonyPlayer> findByUserId(String userId) {
        return store.findById(COLLECTION, userId).map(this::toPlayer);
    }

    @Override
    public List<PonyPlayer> findAll() {
        List<PonyPlayer> all = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findAll(COLLECTION, page, SCAN_PAGE_SIZE);
            docs.forEach(doc -> all.add(toPlayer(doc)));
            if (docs.size() < SCAN_PAGE_SIZE) {
                return all;
            }
            page++;
        }
    }

    @Override
    public List<PonyPlayer> search(int page, int size) {
        List<PonyPlayer> all = findAll();
        all.sort(Comparator.comparingInt(PonyPlayer::getWins).reversed()
                .thenComparing(Comparator.comparingInt(PonyPlayer::getPlayed).reversed()));
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public long count() {
        return store.count(COLLECTION);
    }

    @Override
    public void save(PonyPlayer player) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", player.getUserId());
        doc.put("userId", player.getUserId());
        doc.put("qq", player.getQq());
        doc.put("nickname", player.getNickname());
        doc.put("played", player.getPlayed());
        doc.put("wins", player.getWins());
        doc.put("horsesPlaced", player.getHorsesPlaced());
        doc.put("currentStreak", player.getCurrentStreak());
        doc.put("bestStreak", player.getBestStreak());
        doc.put("updatedAt", player.getUpdatedAt());
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, player.getUserId(), doc);
    }

    private PonyPlayer toPlayer(Map<String, Object> doc) {
        PonyPlayer player = new PonyPlayer(DocumentSupport.string(doc, "userId"));
        player.setQq(DocumentSupport.string(doc, "qq"));
        player.setNickname(DocumentSupport.string(doc, "nickname"));
        player.setPlayed(DocumentSupport.integer(doc, "played", 0));
        player.setWins(DocumentSupport.integer(doc, "wins", 0));
        player.setHorsesPlaced(DocumentSupport.integer(doc, "horsesPlaced", 0));
        player.setCurrentStreak(DocumentSupport.integer(doc, "currentStreak", 0));
        player.setBestStreak(DocumentSupport.integer(doc, "bestStreak", 0));
        player.setUpdatedAt(DocumentSupport.longValue(doc, "updatedAt", 0));
        return player;
    }
}
