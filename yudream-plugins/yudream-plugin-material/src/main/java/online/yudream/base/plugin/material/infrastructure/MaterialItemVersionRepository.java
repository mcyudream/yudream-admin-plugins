package online.yudream.base.plugin.material.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.MaterialItemVersion;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class MaterialItemVersionRepository extends AbstractDocumentRepository<MaterialItemVersion> {
    public static final String COLLECTION = "material_item_versions";

    public MaterialItemVersionRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(MaterialItemVersion version) {
        save(version.id(), version, MaterialItemVersion::toDoc);
    }

    public Optional<MaterialItemVersion> find(String itemId, int version) {
        return findDoc(MaterialItemVersion.idOf(itemId, version)).map(MaterialItemVersion::fromDoc);
    }

    /** 指定子物料的全部版本，版本号倒序。 */
    public List<MaterialItemVersion> listByItem(String itemId) {
        return scanAllDocs().stream()
                .map(MaterialItemVersion::fromDoc)
                .filter(version -> version.itemId().equals(itemId))
                .sorted(Comparator.comparingInt(MaterialItemVersion::version).reversed())
                .toList();
    }

    /** 指定父物料下全部子物料的全部版本（父物料删除时的级联清理用）。 */
    public List<MaterialItemVersion> listByMaterial(String materialId) {
        return scanAllDocs().stream()
                .map(MaterialItemVersion::fromDoc)
                .filter(version -> version.materialId().equals(materialId))
                .toList();
    }
}
