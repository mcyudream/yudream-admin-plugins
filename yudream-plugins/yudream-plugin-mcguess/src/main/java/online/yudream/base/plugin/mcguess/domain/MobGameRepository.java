package online.yudream.base.plugin.mcguess.domain;

import java.util.List;
import java.util.Optional;

public interface MobGameRepository {

    Optional<MobGame> findById(String id);

    /** 该群当前进行中的对局。 */
    Optional<MobGame> findActive(String connectionId, String channelId);

    /** 该群最近一局对局（含已结束），用于终局棋盘展示。 */
    Optional<MobGame> findLatest(String connectionId, String channelId);

    List<MobGame> search(String status, int page, int size);

    long count(String status);

    long countAll();

    void save(MobGame game);

    void delete(String id);
}
