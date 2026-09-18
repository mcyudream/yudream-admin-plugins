package online.yudream.base.plugin.material.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class MaterialItemRepository extends AbstractDocumentRepository<MaterialItem> {
    public static final String COLLECTION = "material_items";

    public MaterialItemRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(MaterialItem item) {
        save(item.id(), item, MaterialItem::toDoc);
    }

    public Optional<MaterialItem> findById(String id) {
        return findDoc(id).map(MaterialItem::fromDoc);
    }

    /** 指定父物料的全部子物料，按 sort 升序（即上传顺序）。 */
    public List<MaterialItem> listByMaterial(String materialId) {
        return scanAllDocs().stream()
                .map(MaterialItem::fromDoc)
                .filter(item -> item.materialId().equals(materialId))
                .sorted(Comparator.comparingInt(MaterialItem::sort))
                .toList();
    }
}
