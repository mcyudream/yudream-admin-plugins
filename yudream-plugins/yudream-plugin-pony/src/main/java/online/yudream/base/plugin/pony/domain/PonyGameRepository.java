package online.yudream.base.plugin.pony.domain;

import java.util.List;
import java.util.Optional;

public interface PonyGameRepository {

    Optional<PonyGame> findActive(String connectionId, String channelId);

    /**
     * 该群最近开局的一局（不限状态，按开始时间取最大）。
     */
    Optional<PonyGame> findLatest(String connectionId, String channelId);

    /**
     * 按开始时间倒序分页；status 为空时不过滤。
     */
    List<PonyGame> search(String status, int page, int size);

    long count(String status);

    long countAll();

    void save(PonyGame game);

    void delete(String id);
}
