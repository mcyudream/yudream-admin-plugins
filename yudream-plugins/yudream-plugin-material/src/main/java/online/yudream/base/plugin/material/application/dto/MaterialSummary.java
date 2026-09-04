package online.yudream.base.plugin.material.application.dto;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.material.domain.Material;

/** 物料列表行 / 详情基础视图。 */
public record MaterialSummary(
        String id,
        String name,
        String ext,
        String type,
        String typeLabel,
        String categoryId,
        String categoryName,
        List<String> tags,
        String ownerId,
        String ownerName,
        String visibility,
        List<String> deptIds,
        List<String> deptNames,
        int currentVersion,
        long size,
        String contentType,
        String status,
        long createdAt,
        long updatedAt
) {
    public static MaterialSummary from(Material material, Map<String, String> categoryNames) {
        return new MaterialSummary(
                material.id(),
                material.name(),
                material.ext(),
                material.type().name(),
                online.yudream.base.plugin.material.domain.MaterialType.labelOf(material.type()),
                material.categoryId(),
                material.categoryId() == null ? null : categoryNames.get(material.categoryId()),
                material.tags(),
                material.ownerId(),
                material.ownerName(),
                material.visibility() == null ? Material.VISIBILITY_PRIVATE : material.visibility(),
                material.deptIds() == null ? List.of() : material.deptIds(),
                material.deptNames() == null ? List.of() : material.deptNames(),
                material.currentVersion(),
                material.size(),
                material.contentType(),
                material.status(),
                material.createdAt(),
                material.updatedAt());
    }
}
