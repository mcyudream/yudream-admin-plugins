package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.ChsiSession;

import java.util.Optional;

public interface ChsiSessionRepository {

    ChsiSession save(ChsiSession session);

    Optional<ChsiSession> findByEmail(String emailLower);

    void delete(String emailLower);
}
