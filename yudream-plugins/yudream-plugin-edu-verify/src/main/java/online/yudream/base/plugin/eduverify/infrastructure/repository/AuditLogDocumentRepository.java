package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.AuditLog;
import online.yudream.base.plugin.eduverify.domain.repo.AuditLogRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditLogDocumentRepository implements AuditLogRepository {

    private static final String COLLECTION = "audit_logs";

    private final PluginDocumentStore documents;

    public AuditLogDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public AuditLog save(AuditLog log) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("at", log.at());
        putIfNotNull(document, "actorType", log.actorType());
        putIfNotNull(document, "actorId", log.actorId());
        putIfNotNull(document, "action", log.action());
        putIfNotNull(document, "channel", log.channel());
        putIfNotNull(document, "emailLower", log.emailLower());
        putIfNotNull(document, "userId", log.userId());
        putIfNotNull(document, "detail", log.detail());
        return toLog(documents.save(COLLECTION, log.id(), document));
    }

    @Override
    public List<AuditLog> listRecent(int page, int size) {
        return documents.findAll(COLLECTION, page, size).stream().map(this::toLog).toList();
    }

    @Override
    public List<AuditLog> findByEmail(String emailLower, int page, int size) {
        if (emailLower == null || emailLower.isBlank()) {
            return List.of();
        }
        return documents.findByField(COLLECTION, "emailLower", emailLower.trim(), page, size).stream()
                .map(this::toLog)
                .toList();
    }

    private void putIfNotNull(Map<String, Object> document, String key, String value) {
        if (value != null) {
            document.put(key, value);
        }
    }

    private AuditLog toLog(Map<String, Object> document) {
        return new AuditLog(
                DocValues.string(document, "id"),
                DocValues.number(document, "at", 0L),
                DocValues.string(document, "actorType"),
                DocValues.string(document, "actorId"),
                DocValues.string(document, "action"),
                DocValues.string(document, "channel"),
                DocValues.string(document, "emailLower"),
                DocValues.string(document, "userId"),
                DocValues.string(document, "detail")
        );
    }
}
