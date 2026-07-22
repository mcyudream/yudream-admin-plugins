package online.yudream.plugin.webcard.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.webcard.domain.WebCardRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DocumentWebCardRepository implements WebCardRepository {
    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;
    public DocumentWebCardRepository(PluginDocumentStore documents, ObjectMapper mapper) { this.documents = documents; this.mapper = mapper; }
    @Override public <T> T save(String collection, String id, T value) {
        Map<String, Object> document = mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        documents.save(collection, id, document); return value;
    }
    @Override public <T> Optional<T> find(String collection, String id, Class<T> type) { return documents.findById(collection, id).map(row -> mapper.convertValue(row, type)); }
    @Override public <T> List<T> page(String collection, int page, int size, Class<T> type) { return documents.findAll(collection, Math.max(1, page), clamp(size)).stream().map(row -> mapper.convertValue(row, type)).toList(); }
    @Override public <T> List<T> findBy(String collection, String field, Object value, int page, int size, Class<T> type) { return documents.findByField(collection, field, value, Math.max(1, page), clamp(size)).stream().map(row -> mapper.convertValue(row, type)).toList(); }
    @Override public long count(String collection) { return documents.count(collection); }
    @Override public <T> boolean updateIfFieldAtMost(String collection, String id, String field, long maximum, T value) {
        Map<String, Object> document = mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        return documents.updateIfFieldAtMost(collection, id, field, maximum, document);
    }
    @Override public void delete(String collection, String id) { documents.delete(collection, id); }
    private int clamp(int size) { return Math.max(1, Math.min(size <= 0 ? 10 : size, 200)); }
}
