package online.yudream.base.plugin.material.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.MaterialShare;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class ShareRepository extends AbstractDocumentRepository<MaterialShare> {
    public static final String COLLECTION = "material_shares";

    public ShareRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(MaterialShare share) {
        save(share.token(), share, MaterialShare::toDoc);
    }

    /** 文档 id 即 token，直接点查。 */
    public Optional<MaterialShare> findByToken(String token) {
        return findDoc(token).map(MaterialShare::fromDoc);
    }

    public List<MaterialShare> listByMaterial(String materialId) {
        return scanAllDocs().stream()
                .map(MaterialShare::fromDoc)
                .filter(share -> materialId.equals(share.materialId()))
                .toList();
    }

    public void deleteByMaterial(String materialId) {
        for (MaterialShare share : listByMaterial(materialId)) {
            delete(share.token());
        }
    }
}
