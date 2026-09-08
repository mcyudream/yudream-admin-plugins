package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.EduVerification;

import java.util.List;
import java.util.Optional;

public interface EduVerificationRepository {

    EduVerification save(EduVerification verification);

    Optional<EduVerification> findById(String id);

    List<EduVerification> findByEmail(String emailLower);

    List<EduVerification> findByUserId(String userId);

    List<EduVerification> findByStatus(String status, int page, int size);

    List<EduVerification> findByChannel(String channel, int page, int size);

    List<EduVerification> listAll();

    long count();

    void delete(String id);
}
