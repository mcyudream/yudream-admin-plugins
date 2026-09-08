package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.ChsiSession;
import online.yudream.base.plugin.eduverify.domain.repo.ChsiSessionRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ChsiSessionDocumentRepository implements ChsiSessionRepository {

    private static final String COLLECTION = "chsi_sessions";

    private final PluginDocumentStore documents;

    public ChsiSessionDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public ChsiSession save(ChsiSession session) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("rndid", session.rndid());
        if (session.captchaUid() != null) {
            document.put("captchaUid", session.captchaUid());
        }
        document.put("attempts", session.attempts());
        if (session.dayBucket() != null) {
            document.put("dayBucket", session.dayBucket());
        }
        document.put("lastCallAt", session.lastCallAt());
        document.put("expiresAt", session.expiresAt());
        return toSession(documents.save(COLLECTION, session.id(), document));
    }

    @Override
    public Optional<ChsiSession> findByEmail(String emailLower) {
        if (emailLower == null || emailLower.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, emailLower.trim()).map(this::toSession);
    }

    @Override
    public void delete(String emailLower) {
        documents.delete(COLLECTION, emailLower);
    }

    private ChsiSession toSession(Map<String, Object> document) {
        return new ChsiSession(
                DocValues.string(document, "id"),
                DocValues.string(document, "rndid"),
                DocValues.string(document, "captchaUid"),
                DocValues.integer(document, "attempts", 0),
                DocValues.string(document, "dayBucket"),
                DocValues.number(document, "lastCallAt", 0L),
                DocValues.number(document, "expiresAt", 0L)
        );
    }
}
