package online.yudream.base.plugin.wordle.domain;

import java.util.List;
import java.util.Optional;

public interface WordlePlayerRepository {

    Optional<WordlePlayer> findByUserId(String userId);

    List<WordlePlayer> findAll();

    /**
     * 按总胜场、总对局倒序分页。
     */
    List<WordlePlayer> search(int page, int size);

    long count();

    void save(WordlePlayer player);
}
