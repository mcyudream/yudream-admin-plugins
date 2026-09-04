package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.QuestionCategory;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class CategoryRepository extends AbstractDocumentRepository<QuestionCategory> {
    public static final String COLLECTION = "qb_categories";

    public CategoryRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(QuestionCategory category) {
        save(category.id(), category, QuestionCategory::toDoc);
    }

    public Optional<QuestionCategory> findById(String id) {
        return findDoc(id).map(QuestionCategory::fromDoc);
    }

    public List<QuestionCategory> listAll() {
        return scanAllDocs().stream()
                .map(QuestionCategory::fromDoc)
                .sorted(Comparator.comparingInt(QuestionCategory::sort).thenComparing(QuestionCategory::createdAt))
                .toList();
    }
}
