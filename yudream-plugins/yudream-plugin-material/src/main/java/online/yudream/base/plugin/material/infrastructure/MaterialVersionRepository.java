package online.yudream.base.plugin.material.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class MaterialVersionRepository extends AbstractDocumentRepository<MaterialVersion> {
    public static final String COLLECTION = "material_versions";

    public MaterialVersionRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(MaterialVersion version) {
        save(version.id(), version, MaterialVersion::toDoc);
    }

    public Optional<MaterialVersion> find(String materialId, int version) {
        return findDoc(MaterialVersion.idOf(materialId, version)).map(MaterialVersion::fromDoc);
    }

    /** 指定物料的全部版本，版本号倒序。 */
    public List<MaterialVersion> listByMaterial(String materialId) {
        return scanAllDocs().stream()
                .map(MaterialVersion::fromDoc)
                .filter(version -> version.materialId().equals(materialId))
                .sorted(Comparator.comparingInt(MaterialVersion::version).reversed())
                .toList();
    }
}
