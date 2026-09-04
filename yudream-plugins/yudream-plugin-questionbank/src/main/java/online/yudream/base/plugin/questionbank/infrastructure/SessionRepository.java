package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class SessionRepository extends AbstractDocumentRepository<PracticeSession> {
    public static final String COLLECTION = "qb_sessions";

    public SessionRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(PracticeSession session) {
        save(session.id(), session, PracticeSession::toDoc);
    }

    public Optional<PracticeSession> findById(String id) {
        return findDoc(id).map(PracticeSession::fromDoc);
    }

    /** 全部会话，字典序（即最新在前）。 */
    public List<PracticeSession> listAll() {
        return scanAllDocs().stream().map(PracticeSession::fromDoc).toList();
    }
}
