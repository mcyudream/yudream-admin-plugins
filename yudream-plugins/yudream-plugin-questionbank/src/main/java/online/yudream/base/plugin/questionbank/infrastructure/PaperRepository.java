package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class PaperRepository extends AbstractDocumentRepository<Paper> {
    public static final String COLLECTION = "qb_papers";

    public PaperRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(Paper paper) {
        save(paper.id(), paper, Paper::toDoc);
    }

    public Optional<Paper> findById(String id) {
        return findDoc(id).map(Paper::fromDoc);
    }

    public List<Paper> listAll() {
        return scanAllDocs().stream()
                .map(Paper::fromDoc)
                .sorted(Comparator.comparing(Paper::createdAt).reversed())
                .toList();
    }
}
