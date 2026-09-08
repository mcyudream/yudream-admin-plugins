package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.EduDomain;

import java.util.List;
import java.util.Optional;

public interface EduDomainRepository {

    EduDomain save(EduDomain domain);

    Optional<EduDomain> findByDomain(String domain);

    List<EduDomain> listAll();

    void delete(String domain);
}
