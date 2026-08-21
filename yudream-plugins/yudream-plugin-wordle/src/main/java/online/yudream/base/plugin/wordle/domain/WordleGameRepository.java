package online.yudream.base.plugin.wordle.domain;

import java.util.List;
import java.util.Optional;

public interface WordleGameRepository {

    Optional<WordleGame> findActive(String connectionId, String channelId);

    /**
     * 该群最近开局的一局（不限状态，按开始时间取最大）。
     */
    Optional<WordleGame> findLatest(String connectionId, String channelId);

    /**
     * 按开始时间倒序分页；status 为空时不过滤。
     */
    List<WordleGame> search(String status, int page, int size);

    long count(String status);

    long countAll();

    void save(WordleGame game);

    void delete(String id);
}
