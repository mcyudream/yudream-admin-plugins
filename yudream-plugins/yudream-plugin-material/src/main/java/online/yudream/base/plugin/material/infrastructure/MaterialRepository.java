package online.yudream.base.plugin.material.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class MaterialRepository extends AbstractDocumentRepository<Material> {
    public static final String COLLECTION = "materials";

    public MaterialRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(Material material) {
        save(material.id(), material, Material::toDoc);
    }

    public Optional<Material> findById(String id) {
        return findDoc(id).map(Material::fromDoc);
    }

    /** 全量扫描；返回顺序即 _id 字典序（id 倒置时间戳 → 最新在前）。 */
    public List<Material> scanAll() {
        return scanAllDocs().stream().map(Material::fromDoc).toList();
    }
}
