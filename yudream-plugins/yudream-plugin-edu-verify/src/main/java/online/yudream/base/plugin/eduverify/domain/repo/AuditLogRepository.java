package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.AuditLog;

import java.util.List;

public interface AuditLogRepository {

    AuditLog save(AuditLog log);

    List<AuditLog> listRecent(int page, int size);

    List<AuditLog> findByEmail(String emailLower, int page, int size);
}
