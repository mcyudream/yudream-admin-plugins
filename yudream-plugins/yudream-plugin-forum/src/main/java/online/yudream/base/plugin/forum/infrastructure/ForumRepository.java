package online.yudream.base.plugin.forum.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class ForumRepository {
    public static final String CATEGORIES = "forum_categories";
    public static final String POSTS = "forum_posts";
    public static final String COMMENTS = "forum_comments";
    public static final String INTERACTIONS = "forum_interactions";
    public static final String SETTINGS = "forum_settings";
    private static final int SCAN = 200;
    private final PluginDocumentStore store;
    public ForumRepository(PluginDocumentStore store) { this.store = store; }
    public void save(String collection, String id, Map<String, Object> doc) { store.save(collection, id, doc); }
    public Optional<Map<String, Object>> find(String collection, String id) { return store.findById(collection, id); }
    public void delete(String collection, String id) { store.delete(collection, id); }
    public List<Map<String, Object>> all(String collection) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int page = 1;; page++) {
            List<Map<String, Object>> batch = store.findAll(collection, page, SCAN);
            if (batch.isEmpty()) return result;
            result.addAll(batch);
            if (batch.size() < SCAN) return result;
        }
    }
}
