package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.EduDomain;
import online.yudream.base.plugin.eduverify.domain.repo.EduDomainRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EduDomainDocumentRepository implements EduDomainRepository {

    private static final int SCAN_PAGE_SIZE = 200;
    private static final String COLLECTION = "domains";

    private final PluginDocumentStore documents;

    public EduDomainDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public EduDomain save(EduDomain domain) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("domain", domain.domain());
        putIfNotNull(document, "chineseName", domain.chineseName());
        putIfNotNull(document, "englishName", domain.englishName());
        document.put("enabled", domain.enabled());
        document.put("source", domain.source());
        document.put("createdAt", domain.createdAt());
        document.put("updatedAt", domain.updatedAt());
        return toDomain(documents.save(COLLECTION, domain.domain(), document));
    }

    @Override
    public Optional<EduDomain> findByDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, domain.trim().toLowerCase()).map(this::toDomain);
    }

    @Override
    public List<EduDomain> listAll() {
        List<EduDomain> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<EduDomain> batch = documents.findAll(COLLECTION, page, SCAN_PAGE_SIZE).stream()
                    .map(this::toDomain)
                    .toList();
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    @Override
    public void delete(String domain) {
        documents.delete(COLLECTION, domain);
    }

    private EduDomain toDomain(Map<String, Object> document) {
        long createdAt = DocValues.number(document, "createdAt", 0L);
        return new EduDomain(
                DocValues.string(document, "id"),
                DocValues.string(document, "chineseName"),
                DocValues.string(document, "englishName"),
                DocValues.bool(document, "enabled", true),
                DocValues.string(document, "source"),
                createdAt,
                DocValues.number(document, "updatedAt", createdAt)
        );
    }

    private void putIfNotNull(Map<String, Object> document, String key, String value) {
        if (value != null && !value.isBlank()) {
            document.put(key, value.trim());
        }
    }
}
