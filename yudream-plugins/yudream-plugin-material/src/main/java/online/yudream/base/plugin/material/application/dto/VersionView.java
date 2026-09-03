package online.yudream.base.plugin.material.application.dto;

import online.yudream.base.plugin.material.domain.MaterialVersion;

/** 版本视图。 */
public record VersionView(
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
    public static VersionView from(MaterialVersion version, int currentVersion) {
        return new VersionView(
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
