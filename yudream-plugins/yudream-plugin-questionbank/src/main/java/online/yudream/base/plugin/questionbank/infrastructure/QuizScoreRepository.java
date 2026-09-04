package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.QuizScore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class QuizScoreRepository extends AbstractDocumentRepository<QuizScore> {
    public static final String COLLECTION = "qb_quiz_scores";

    public QuizScoreRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(QuizScore score) {
        save(score.id(), score, QuizScore::toDoc);
    }

    public Optional<QuizScore> findById(String id) {
        return findDoc(id).map(QuizScore::fromDoc);
    }

    public List<QuizScore> listAll() {
        return scanAllDocs().stream().map(QuizScore::fromDoc).toList();
    }
}
