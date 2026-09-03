package online.yudream.base.plugin.material.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.MaterialCategory;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class CategoryRepository extends AbstractDocumentRepository<MaterialCategory> {
    public static final String COLLECTION = "material_categories";

    public CategoryRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(MaterialCategory category) {
        save(category.id(), category, MaterialCategory::toDoc);
    }

    public Optional<MaterialCategory> findById(String id) {
        return findDoc(id).map(MaterialCategory::fromDoc);
    }

    public List<MaterialCategory> listAll() {
        return scanAllDocs().stream()
                .map(MaterialCategory::fromDoc)
                .sorted(Comparator.comparingInt(MaterialCategory::sort).thenComparing(MaterialCategory::createdAt))
                .toList();
    }
}
