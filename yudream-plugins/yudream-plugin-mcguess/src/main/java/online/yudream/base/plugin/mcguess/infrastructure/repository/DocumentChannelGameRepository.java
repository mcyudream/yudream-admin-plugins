package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.ChannelGame;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群回合制对局文档仓储的通用实现：子类只需提供集合名与文档 ↔ 对局的转换。
 */
public abstract class DocumentChannelGameRepository<T extends ChannelGame> {

    private final PluginDocumentStore store;
    private final String collection;

    protected DocumentChannelGameRepository(PluginDocumentStore store, String collection) {
        this.store = store;
        this.collection = collection;
    }

    /** 文档 → 对局（含状态重放）。 */
    protected abstract T toGame(Map<String, Object> doc);

    /** 对局 → 文档（无需 stripNulls，save 统一处理）。 */
    protected abstract Map<String, Object> toDoc(T game);

    public Optional<T> findById(String id) {
        return store.findById(collection, id).map(this::toGame);
    }

    public Optional<T> findActive(String connectionId, String channelId) {
        String channelKey = connectionId + ":" + channelId;
        return DocumentSupport.scanByField(store, collection, "channelKey", channelKey).stream()
                .map(this::toGame)
                .filter(ChannelGame::isPlaying)
                .findFirst();
    }

    public Optional<T> findLatest(String connectionId, String channelId) {
        String channelKey = connectionId + ":" + channelId;
        return DocumentSupport.scanByField(store, collection, "channelKey", channelKey).stream()
                .map(this::toGame)
                .max(Comparator.comparingLong(ChannelGame::getStartedAt));
    }

    public List<T> search(String status, int page, int size) {
        List<T> all = collect(status);
        all.sort(Comparator.comparingLong(ChannelGame::getStartedAt).reversed());
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        return new ArrayList<>(all.subList(from, Math.min(from + size, all.size())));
    }

    public long count(String status) {
        if (status == null || status.isBlank()) {
            return store.count(collection);
        }
        return collect(status).size();
    }

    public long countAll() {
        return store.count(collection);
    }

    public void save(T game) {
        Map<String, Object> doc = toDoc(game);
        DocumentSupport.stripNulls(doc);
        store.save(collection, game.getId(), doc);
    }

    public void delete(String id) {
        store.delete(collection, id);
    }

    private List<T> collect(String status) {
        List<Map<String, Object>> docs = status == null || status.isBlank()
                ? DocumentSupport.scanAll(store, collection)
                : DocumentSupport.scanByField(store, collection, "status", status);
        List<T> all = new ArrayList<>();
        docs.forEach(doc -> all.add(toGame(doc)));
        return all;
    }
}
