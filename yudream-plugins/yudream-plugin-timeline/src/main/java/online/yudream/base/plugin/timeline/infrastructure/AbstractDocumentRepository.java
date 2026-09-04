package online.yudream.base.plugin.timeline.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/**
 * 文档仓储基类：宿主 findByField 存在「先分页后过滤」回归，全量读取一律 findAll 按 200/页扫描。
 */
abstract class AbstractDocumentRepository<T> {
    protected static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;
    private final String collection;

    protected AbstractDocumentRepository(PluginDocumentStore documents, String collection) {
        this.documents = documents;
        this.collection = collection;
    }

    public String collection() {
        return collection;
    }

    public void save(String id, T value, Function<T, Map<String, Object>> mapper) {
        documents.save(collection, id, mapper.apply(value));
    }

    public Optional<Map<String, Object>> findDoc(String id) {
        return documents.findById(collection, id);
    }

    public void delete(String id) {
        documents.delete(collection, id);
    }

    protected List<Map<String, Object>> scanAllDocs() {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(collection, page, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return all;
    }
}
