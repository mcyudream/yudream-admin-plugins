package online.yudream.base.plugin.wordle.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.wordle.domain.WordlePlayer;
import online.yudream.base.plugin.wordle.domain.WordlePlayerRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WordleDocumentPlayerRepository implements WordlePlayerRepository {

    private static final String COLLECTION = "wordle_players";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public WordleDocumentPlayerRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<WordlePlayer> findByUserId(String userId) {
        return store.findById(COLLECTION, userId).map(this::toPlayer);
    }

    @Override
    public List<WordlePlayer> findAll() {
        List<WordlePlayer> all = new ArrayList<>();
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
    public List<WordlePlayer> search(int page, int size) {
        List<WordlePlayer> all = findAll();
        all.sort(Comparator.comparingInt(WordlePlayer::totalWins).reversed()
                .thenComparing(Comparator.comparingInt(WordlePlayer::totalPlayed).reversed()));
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public long count() {
        return store.count(COLLECTION);
    }

    @Override
    public void save(WordlePlayer player) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", player.getUserId());
        doc.put("userId", player.getUserId());
        doc.put("qq", player.getQq());
        doc.put("nickname", player.getNickname());
        doc.put("englishPlayed", player.getEnglishPlayed());
        doc.put("englishWins", player.getEnglishWins());
        doc.put("idiomPlayed", player.getIdiomPlayed());
        doc.put("idiomWins", player.getIdiomWins());
        doc.put("currentStreak", player.getCurrentStreak());
        doc.put("bestStreak", player.getBestStreak());
        doc.put("winDistribution", new HashMap<>(player.getWinDistribution()));
        doc.put("updatedAt", player.getUpdatedAt());
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, player.getUserId(), doc);
    }

    private WordlePlayer toPlayer(Map<String, Object> doc) {
        WordlePlayer player = new WordlePlayer(DocumentSupport.string(doc, "userId"));
        player.setQq(DocumentSupport.string(doc, "qq"));
        player.setNickname(DocumentSupport.string(doc, "nickname"));
        player.setEnglishPlayed(DocumentSupport.integer(doc, "englishPlayed", 0));
        player.setEnglishWins(DocumentSupport.integer(doc, "englishWins", 0));
        player.setIdiomPlayed(DocumentSupport.integer(doc, "idiomPlayed", 0));
        player.setIdiomWins(DocumentSupport.integer(doc, "idiomWins", 0));
        player.setCurrentStreak(DocumentSupport.integer(doc, "currentStreak", 0));
        player.setBestStreak(DocumentSupport.integer(doc, "bestStreak", 0));
        player.getWinDistribution().putAll(DocumentSupport.intMap(doc, "winDistribution"));
        player.setUpdatedAt(DocumentSupport.longValue(doc, "updatedAt", 0));
        return player;
    }
}
