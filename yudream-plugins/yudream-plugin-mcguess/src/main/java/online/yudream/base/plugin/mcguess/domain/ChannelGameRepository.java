package online.yudream.base.plugin.mcguess.domain;

import java.util.List;
import java.util.Optional;

/**
 * 群回合制对局仓储的通用接口（与既有三种模式仓储方法集一致）。
 */
public interface ChannelGameRepository<T extends ChannelGame> {

    Optional<T> findById(String id);

    /** 该群当前进行中的对局。 */
    Optional<T> findActive(String connectionId, String channelId);

    /** 该群最近一局对局（含已结束），用于终局棋盘展示。 */
    Optional<T> findLatest(String connectionId, String channelId);

    List<T> search(String status, int page, int size);

    long count(String status);

    long countAll();

    void save(T game);

    void delete(String id);
}
