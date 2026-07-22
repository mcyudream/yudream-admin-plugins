package online.yudream.plugin.webcard.domain;

import java.util.List;
import java.util.Optional;

public interface WebCardRepository {
    <T> T save(String collection, String id, T value);
    <T> Optional<T> find(String collection, String id, Class<T> type);
    <T> List<T> page(String collection, int page, int size, Class<T> type);
    <T> List<T> findBy(String collection, String field, Object value, int page, int size, Class<T> type);
    long count(String collection);
    <T> boolean updateIfFieldAtMost(String collection, String id, String field, long maximum, T value);
    void delete(String collection, String id);
}
