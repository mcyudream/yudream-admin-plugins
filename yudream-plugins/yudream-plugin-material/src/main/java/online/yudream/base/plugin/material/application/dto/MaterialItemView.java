package online.yudream.base.plugin.material.application.dto;

import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.material.domain.MaterialType;

/** 子物料视图（父物料详情页的子物料清单行）。 */
public record MaterialItemView(
        String id,
        String materialId,
        String name,
        int sort,
        String ext,
        String type,
        String typeLabel,
        long size,
        String contentType,
        int currentVersion,
        long createdAt,
        long updatedAt
) {
    public static MaterialItemView from(MaterialItem item) {
        return new MaterialItemView(
                item.id(),
                item.materialId(),
                item.name(),
                item.sort(),
                item.ext(),
                item.type().name(),
                MaterialType.labelOf(item.type()),
                item.size(),
                item.contentType(),
                item.currentVersion(),
                item.createdAt(),
                item.updatedAt());
    }
}
