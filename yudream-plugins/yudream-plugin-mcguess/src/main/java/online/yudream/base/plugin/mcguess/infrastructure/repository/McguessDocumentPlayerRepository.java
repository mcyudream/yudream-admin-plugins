package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McguessDocumentPlayerRepository implements McguessPlayerRepository {

    private static final String COLLECTION = "mcguess_players";

    private final PluginDocumentStore store;

    public McguessDocumentPlayerRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<McguessPlayer> findByUserId(String userId) {
        return store.findById(COLLECTION, userId).map(this::toPlayer);
    }

    @Override
    public List<McguessPlayer> search(int page, int size) {
        List<McguessPlayer> all = new ArrayList<>();
        DocumentSupport.scanAll(store, COLLECTION).forEach(doc -> all.add(toPlayer(doc)));
        all.sort(Comparator.comparingInt(McguessPlayer::wins).reversed()
                .thenComparing(Comparator.comparingInt(McguessPlayer::played).reversed()));
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        return new ArrayList<>(all.subList(from, Math.min(from + size, all.size())));
    }

    @Override
    public long count() {
        return store.count(COLLECTION);
    }

    @Override
    public void save(McguessPlayer player) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", player.getUserId());
        doc.put("qq", player.getQq());
        doc.put("nickname", player.getNickname());
        doc.put("itemPlayed", player.getItemPlayed());
        doc.put("itemWins", player.getItemWins());
        doc.put("mobPlayed", player.getMobPlayed());
        doc.put("mobWins", player.getMobWins());
        doc.put("recipePlayed", player.getRecipePlayed());
        doc.put("recipeWins", player.getRecipeWins());
        doc.put("fogPlayed", player.getFogPlayed());
        doc.put("fogWins", player.getFogWins());
        doc.put("quizPlayed", player.getQuizPlayed());
        doc.put("quizWins", player.getQuizWins());
        doc.put("bingoPlayed", player.getBingoPlayed());
        doc.put("bingoWins", player.getBingoWins());
        doc.put("spotPlayed", player.getSpotPlayed());
        doc.put("spotWins", player.getSpotWins());
        doc.put("totalGuesses", player.getTotalGuesses());
        doc.put("holBest", player.getHolBest());
        doc.put("holA", player.getHolA());
        doc.put("holB", player.getHolB());
        doc.put("holStreak", player.getHolStreak());
        doc.put("collection", player.collectionItems());
        doc.put("updatedAt", player.getUpdatedAt());
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, player.getUserId(), doc);
    }

    private McguessPlayer toPlayer(Map<String, Object> doc) {
        McguessPlayer player = new McguessPlayer(DocumentSupport.string(doc, "id"));
        player.setQq(DocumentSupport.string(doc, "qq"));
        player.setNickname(DocumentSupport.string(doc, "nickname"));
        player.restoreStats(
                DocumentSupport.integer(doc, "itemPlayed", 0),
                DocumentSupport.integer(doc, "itemWins", 0),
                DocumentSupport.integer(doc, "mobPlayed", 0),
                DocumentSupport.integer(doc, "mobWins", 0),
                DocumentSupport.integer(doc, "recipePlayed", 0),
                DocumentSupport.integer(doc, "recipeWins", 0),
                DocumentSupport.integer(doc, "totalGuesses", 0),
                DocumentSupport.longValue(doc, "updatedAt", 0));
        player.restoreExtras(
                DocumentSupport.integer(doc, "fogPlayed", 0),
                DocumentSupport.integer(doc, "fogWins", 0),
                DocumentSupport.integer(doc, "quizPlayed", 0),
                DocumentSupport.integer(doc, "quizWins", 0),
                DocumentSupport.integer(doc, "bingoPlayed", 0),
                DocumentSupport.integer(doc, "bingoWins", 0),
                DocumentSupport.integer(doc, "spotPlayed", 0),
                DocumentSupport.integer(doc, "spotWins", 0),
                DocumentSupport.integer(doc, "holBest", 0),
                DocumentSupport.string(doc, "holA"),
                DocumentSupport.string(doc, "holB"),
                DocumentSupport.integer(doc, "holStreak", 0),
                DocumentSupport.stringList(doc, "collection"));
        return player;
    }
}
