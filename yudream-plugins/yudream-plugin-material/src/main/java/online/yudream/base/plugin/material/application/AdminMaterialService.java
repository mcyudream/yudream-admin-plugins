package online.yudream.base.plugin.material.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialSummary;
import online.yudream.base.plugin.material.domain.Material;

/** 管理端物料用例：跨用户范围，仅 /admin/** 端点调用。 */
public final class AdminMaterialService {
    private final MaterialService materialService;
    private final online.yudream.base.plugin.material.infrastructure.MaterialRepository materials;

    public AdminMaterialService(MaterialService materialService,
                                online.yudream.base.plugin.material.infrastructure.MaterialRepository materials) {
        this.materialService = materialService;
        this.materials = materials;
    }

    public PageResult<MaterialSummary> list(String keyword, String type, String categoryId,
                                            String owner, String status, int page, int size) {
        String statusFilter = status == null ? "" : status.trim();
        String typeFilter = type == null ? "" : type.trim();
        String categoryFilter = categoryId == null ? "" : categoryId.trim();
        String keywordFilter = keyword == null ? "" : keyword.trim();
        String ownerFilter = owner == null ? "" : owner.trim();
        List<Material> filtered = materials.scanAll().stream()
                .filter(material -> statusFilter.isBlank() || statusFilter.equalsIgnoreCase(material.status()))
                .filter(material -> typeFilter.isBlank() || material.type().name().equalsIgnoreCase(typeFilter))
                .filter(material -> categoryFilter.isBlank() || categoryFilter.equals(material.categoryId()))
                .filter(material -> keywordFilter.isBlank() || MaterialService.matchesKeyword(material, keywordFilter))
                .filter(material -> ownerFilter.isBlank()
                        || (material.ownerName() != null && material.ownerName().toLowerCase(Locale.ROOT).contains(ownerFilter.toLowerCase(Locale.ROOT)))
                        || material.ownerId().contains(ownerFilter))
                .toList();
        Map<String, String> names = materialService.categoryNames();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to).stream()
                .map(material -> MaterialSummary.from(material, names))
                .toList(), filtered.size());
    }

    public MaterialDetail detail(String id) {
        return materialService.toDetail(materialService.requireAny(id));
    }

    public MaterialDetail setStatus(String id, String status) {
        Material material = materialService.requireAny(id);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Material.STATUS_ACTIVE.equals(normalized) && !Material.STATUS_ARCHIVED.equals(normalized)) {
            throw new IllegalArgumentException("状态仅支持 ACTIVE / ARCHIVED");
        }
        Material updated = material.withStatus(normalized, System.currentTimeMillis());
        materials.save(updated);
        return materialService.toDetail(updated);
    }

    public void delete(String id) {
        materialService.deleteCascade(materialService.requireAny(id));
    }
}
