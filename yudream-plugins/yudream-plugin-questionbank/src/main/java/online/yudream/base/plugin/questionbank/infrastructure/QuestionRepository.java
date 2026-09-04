package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class QuestionRepository extends AbstractDocumentRepository<Question> {
    public static final String COLLECTION = "qb_questions";

    public QuestionRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(Question question) {
        save(question.id(), question, Question::toDoc);
    }

    public Optional<Question> findById(String id) {
        return findDoc(id).map(Question::fromDoc);
    }

    /** 全部题目，字典序（即最新在前）。 */
    public List<Question> listAll() {
        return scanAllDocs().stream().map(Question::fromDoc).toList();
    }
}
