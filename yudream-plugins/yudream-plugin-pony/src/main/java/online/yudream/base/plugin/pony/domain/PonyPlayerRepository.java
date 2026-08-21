package online.yudream.base.plugin.pony.domain;

import java.util.List;
import java.util.Optional;

public interface PonyPlayerRepository {

    Optional<PonyPlayer> findByUserId(String userId);

    List<PonyPlayer> findAll();

    /**
     * 按胜场、对局数倒序分页。
     */
    List<PonyPlayer> search(int page, int size);

    long count();

    void save(PonyPlayer player);
}
