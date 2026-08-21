package online.yudream.base.plugin.mcguess.domain;

import java.util.List;
import java.util.Optional;

public interface McguessPlayerRepository {

    Optional<McguessPlayer> findByUserId(String userId);

    List<McguessPlayer> search(int page, int size);

    long count();

    void save(McguessPlayer player);
}
