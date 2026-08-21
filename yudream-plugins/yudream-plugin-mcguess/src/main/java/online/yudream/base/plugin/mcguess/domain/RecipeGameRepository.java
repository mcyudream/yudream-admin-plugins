package online.yudream.base.plugin.mcguess.domain;

import java.util.List;
import java.util.Optional;

public interface RecipeGameRepository {

    Optional<RecipeGame> findById(String id);

    /** 该群当前进行中的对局。 */
    Optional<RecipeGame> findActive(String connectionId, String channelId);

    /** 该群最近一局对局（含已结束），用于终局棋盘展示。 */
    Optional<RecipeGame> findLatest(String connectionId, String channelId);

    List<RecipeGame> search(String status, int page, int size);

    long count(String status);

    long countAll();

    void save(RecipeGame game);

    void delete(String id);
}
