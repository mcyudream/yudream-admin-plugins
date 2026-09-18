package online.yudream.base.plugin.material.application.dto;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.material.domain.Material;

/**
 * 物料列表行 / 详情基础视图。
 *
 * <p>itemCount 为子物料数量；mainFilePresent 为 false 时该物料是组合物料（无主文件，文件全部来自子物料），
 * 此时 ext/size 为空、type 为 OTHER，前端应按组合物料渲染而不是按类型图标。
 * previewItemId 为组合物料「预览主文件」所指的子物料 id（未指定时为空，预览回退第一个子物料）。
 */
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
        int itemCount,
        boolean mainFilePresent,
        String previewItemId,
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
                material.itemCount(),
                material.mainFilePresent(),
                material.previewItemId(),
                material.createdAt(),
                material.updatedAt());
    }
}
