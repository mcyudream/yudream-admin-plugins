package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.EduVerification;
import online.yudream.base.plugin.eduverify.domain.repo.EduVerificationRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EduVerificationDocumentRepository implements EduVerificationRepository {

    private static final int SCAN_PAGE_SIZE = 200;
    private static final String COLLECTION = "verifications";

    private final PluginDocumentStore documents;

    public EduVerificationDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public EduVerification save(EduVerification verification) {
        Map<String, Object> document = new LinkedHashMap<>();
        putIfNotNull(document, "emailLower", verification.emailLower());
        putIfNotNull(document, "userId", verification.userId());
        putIfNotNull(document, "channel", verification.channel());
        putIfNotNull(document, "status", verification.status());
        putIfNotNull(document, "realName", verification.realName());
        putIfNotNull(document, "schoolName", verification.schoolName());
        putIfNotNull(document, "note", verification.note());
        putIfNotNull(document, "vcode", verification.vcode());
        document.put("materials", verification.materials());
        putIfNotNull(document, "reason", verification.reason());
        document.put("submittedAt", verification.submittedAt());
        document.put("decidedAt", verification.decidedAt());
        putIfNotNull(document, "decidedBy", verification.decidedBy());
        document.put("expiresAt", verification.expiresAt());
        document.put("createdAt", verification.createdAt());
        document.put("updatedAt", verification.updatedAt());
        return toVerification(documents.save(COLLECTION, verification.id(), document));
    }

    @Override
    public Optional<EduVerification> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, id).map(this::toVerification);
    }

    @Override
    public List<EduVerification> findByEmail(String emailLower) {
        if (emailLower == null || emailLower.isBlank()) {
            return List.of();
        }
        return documents.findByField(COLLECTION, "emailLower", emailLower.trim(), 1, SCAN_PAGE_SIZE).stream()
                .map(this::toVerification)
                .toList();
    }

    @Override
    public List<EduVerification> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return documents.findByField(COLLECTION, "userId", userId.trim(), 1, SCAN_PAGE_SIZE).stream()
                .map(this::toVerification)
                .toList();
    }

    @Override
    public List<EduVerification> findByStatus(String status, int page, int size) {
        return documents.findByField(COLLECTION, "status", status, page, size).stream()
                .map(this::toVerification)
                .toList();
    }

    @Override
    public List<EduVerification> findByChannel(String channel, int page, int size) {
        return documents.findByField(COLLECTION, "channel", channel, page, size).stream()
                .map(this::toVerification)
                .toList();
    }

    @Override
    public List<EduVerification> listAll() {
        List<EduVerification> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<EduVerification> batch = documents.findAll(COLLECTION, page, SCAN_PAGE_SIZE).stream()
                    .map(this::toVerification)
                    .toList();
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    @Override
    public long count() {
        return documents.count(COLLECTION);
    }

    @Override
    public void delete(String id) {
        documents.delete(COLLECTION, id);
    }

    private void putIfNotNull(Map<String, Object> document, String key, String value) {
        if (value != null) {
            document.put(key, value);
        }
    }

    private EduVerification toVerification(Map<String, Object> document) {
        return new EduVerification(
                DocValues.string(document, "id"),
                DocValues.string(document, "emailLower"),
                DocValues.string(document, "userId"),
                DocValues.string(document, "channel"),
                DocValues.string(document, "status"),
                DocValues.string(document, "realName"),
                DocValues.string(document, "schoolName"),
                DocValues.string(document, "note"),
                DocValues.string(document, "vcode"),
                DocValues.mapList(document, "materials"),
                DocValues.string(document, "reason"),
                DocValues.number(document, "submittedAt", 0L),
                DocValues.number(document, "decidedAt", 0L),
                DocValues.string(document, "decidedBy"),
                DocValues.number(document, "expiresAt", 0L),
                DocValues.number(document, "createdAt", 0L),
                DocValues.number(document, "updatedAt", 0L)
        );
    }
}
