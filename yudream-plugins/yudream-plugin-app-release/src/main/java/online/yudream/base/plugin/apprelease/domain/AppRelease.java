package online.yudream.base.plugin.apprelease.domain;

/**
 * 更新包版本聚合：一个平台上的一条发布记录。
 * <p>
 * versionCode 是整数版本线（客户端比较依据），versionName 仅展示；
 * forceUpdate 表示"该版本为强制更新线"，客户端低于它时必须升级才能继续使用。
 */
public record AppRelease(
        String id,
        String platform,
        int versionCode,
        String versionName,
        String changelog,
        String fileName,
        long fileSize,
        String sha256,
        boolean forceUpdate,
        boolean published,
        long createdAt,
        long publishedAt
) {

    public AppRelease {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("版本 id 不能为空");
        }
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("平台不能为空");
        }
        if (versionCode <= 0) {
            throw new IllegalArgumentException("versionCode 必须为正整数");
        }
        if (versionName == null || versionName.isBlank()) {
            throw new IllegalArgumentException("versionName 不能为空");
        }
        id = id.trim();
        platform = platform.trim().toLowerCase();
        versionName = versionName.trim();
        changelog = changelog == null ? "" : changelog.trim();
        fileName = fileName == null ? "" : fileName.trim();
    }

    public AppRelease withPublished(boolean target) {
        return new AppRelease(id, platform, versionCode, versionName, changelog, fileName, fileSize, sha256,
                forceUpdate, target, createdAt, target ? System.currentTimeMillis() : 0L);
    }
}
