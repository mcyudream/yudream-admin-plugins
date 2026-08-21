package online.yudream.base.plugin.wordle.domain;

import java.util.List;
import java.util.Optional;

public interface WordEntryRepository {

    Optional<WordEntry> findById(String id);

    /**
     * 按创建时间倒序分页；mode / keyword 为空时不过滤，keyword 匹配词条内容。
     */
    List<WordEntry> search(String mode, String keyword, int page, int size);

    long count(String mode, String keyword);

    List<WordEntry> findEnabled(WordleMode mode);

    long countAll();

    void save(WordEntry entry);

    void delete(String id);
}
