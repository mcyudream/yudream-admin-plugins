package online.yudream.base.plugin.material.application.dto;

import online.yudream.base.plugin.material.domain.MaterialItemVersion;

/** 子物料版本视图，字段与 {@link VersionView} 对齐，便于前端复用同一套版本表格。 */
public record MaterialItemVersionView(
        int version,
        String originalName,
        long size,
        String contentType,
        String note,
        String uploaderId,
        String uploaderName,
        long createdAt,
        boolean current
) {
    public static MaterialItemVersionView from(MaterialItemVersion version, int currentVersion) {
        return new MaterialItemVersionView(
                version.version(),
                version.originalName(),
                version.size(),
                version.contentType(),
                version.note(),
                version.uploaderId(),
                version.uploaderName(),
                version.createdAt(),
                version.version() == currentVersion);
    }
}
