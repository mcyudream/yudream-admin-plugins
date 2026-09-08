package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.StagingMaterial;

import java.util.List;
import java.util.Optional;

public interface StagingMaterialRepository {

    StagingMaterial save(StagingMaterial material);

    Optional<StagingMaterial> findById(String id);

    List<StagingMaterial> findByEmail(String emailLower);

    List<StagingMaterial> listAll();

    void delete(String id);
}
