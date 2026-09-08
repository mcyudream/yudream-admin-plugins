package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.StagingMaterial;
import online.yudream.base.plugin.eduverify.domain.repo.StagingMaterialRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StagingMaterialDocumentRepository implements StagingMaterialRepository {

    private static final String COLLECTION = "material_staging";
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public StagingMaterialDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public StagingMaterial save(StagingMaterial material) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("emailLower", material.emailLower());
        document.put("clientKey", material.clientKey());
        document.put("objectKey", material.objectKey());
        document.put("filename", material.filename());
        document.put("contentType", material.contentType());
        document.put("size", material.size());
        document.put("sha256", material.sha256());
        document.put("createdAt", material.createdAt());
        document.put("expiresAt", material.expiresAt());
        return toMaterial(documents.save(COLLECTION, material.id(), document));
    }

    @Override
    public Optional<StagingMaterial> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, id.trim()).map(this::toMaterial);
    }

    @Override
    public List<StagingMaterial> findByEmail(String emailLower) {
        if (emailLower == null || emailLower.isBlank()) {
            return List.of();
        }
        List<StagingMaterial> matched = new ArrayList<>();
        int page = 1;
        while (true) {
            List<StagingMaterial> batch = documents.findByField(COLLECTION, "emailLower", emailLower.trim(), page, PAGE_SIZE)
                    .stream()
                    .map(this::toMaterial)
                    .toList();
            matched.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                return matched;
            }
            page++;
        }
    }

    @Override
    public List<StagingMaterial> listAll() {
        List<StagingMaterial> all = new ArrayList<>();
        int page = 1;
        while (true) {
            List<StagingMaterial> batch = documents.findAll(COLLECTION, page, PAGE_SIZE).stream()
                    .map(this::toMaterial)
                    .toList();
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                return all;
            }
            page++;
        }
    }

    @Override
    public void delete(String id) {
        if (id != null && !id.isBlank()) {
            documents.delete(COLLECTION, id);
        }
    }

    private StagingMaterial toMaterial(Map<String, Object> document) {
        return new StagingMaterial(
                DocValues.string(document, "id"),
                DocValues.string(document, "emailLower"),
                DocValues.string(document, "clientKey"),
                DocValues.string(document, "objectKey"),
                DocValues.string(document, "filename"),
                DocValues.string(document, "contentType"),
                DocValues.number(document, "size", 0L),
                DocValues.string(document, "sha256"),
                DocValues.number(document, "createdAt", 0L),
                DocValues.number(document, "expiresAt", 0L)
        );
    }
}
