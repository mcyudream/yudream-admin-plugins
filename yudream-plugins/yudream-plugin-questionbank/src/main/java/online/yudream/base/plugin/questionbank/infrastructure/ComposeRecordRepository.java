package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.ComposeRecord;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class ComposeRecordRepository extends AbstractDocumentRepository<ComposeRecord> {
    public static final String COLLECTION = "qb_compose_records";

    public ComposeRecordRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(ComposeRecord record) {
        save(record.id(), record, ComposeRecord::toDoc);
    }

    public Optional<ComposeRecord> findById(String id) {
        return findDoc(id).map(ComposeRecord::fromDoc);
    }

    public List<ComposeRecord> listAll() {
        return scanAllDocs().stream()
                .map(ComposeRecord::fromDoc)
                .sorted(Comparator.comparing(ComposeRecord::createdAt).reversed())
                .toList();
    }
}
