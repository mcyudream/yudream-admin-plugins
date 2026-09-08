package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.EmailCode;

import java.util.Optional;

public interface EmailCodeRepository {

    EmailCode save(EmailCode code);

    Optional<EmailCode> findByEmail(String emailLower);

    void delete(String emailLower);
}
