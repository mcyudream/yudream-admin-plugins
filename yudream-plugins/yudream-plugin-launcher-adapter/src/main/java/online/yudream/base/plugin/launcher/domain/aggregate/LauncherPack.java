package online.yudream.base.plugin.launcher.domain.aggregate;

import java.util.ArrayList;
import java.util.List;

/**
 * mrpack 整合包聚合根。
 * <p>
 * 版本内容由 {@link LauncherPackVersion} 管理；本聚合只持有元数据与推荐版本指针。
 * 启动器通过 {@code recommendedVersionId} 拉取 index，并按 hash 对比本地实例决定是否增量更新。
 */
public record LauncherPack(
        String id,
        String name,
        String description,
        String icon,
        String recommendedVersionId,
        List<String> retainedVersionIds,
        long createdAt,
        long updatedAt,
        long revision
) {

    public LauncherPack {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("packId 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pack 名称不能为空");
        }
        id = id.trim();
        name = name.trim();
        description = description == null ? "" : description.trim();
        icon = icon == null ? "" : icon.trim();
        retainedVersionIds = retainedVersionIds == null ? List.of() : List.copyOf(retainedVersionIds);
    }

    public LauncherPack withRecommendedVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("recommendedVersionId 不能为空");
        }
        return new LauncherPack(id, name, description, icon, versionId.trim(), retainedVersionIds,
                createdAt, System.currentTimeMillis(), revision);
    }

    public LauncherPack retainVersion(String versionId, int keepLatest) {
        if (versionId == null || versionId.isBlank()) {
            return this;
        }
        if (keepLatest <= 0) {
            throw new IllegalArgumentException("keepLatest 必须为正数");
        }
        List<String> ids = new ArrayList<>(retainedVersionIds);
        if (!ids.contains(versionId)) {
            ids.add(versionId);
        }
        while (ids.size() > keepLatest) {
            ids.removeFirst();
        }
        return new LauncherPack(id, name, description, icon, recommendedVersionId, ids,
                createdAt, System.currentTimeMillis(), revision);
    }

    /** 发布/覆盖物变更前自增对账基准；返回值即新版本应 stamp 的 revision。 */
    public LauncherPack bumpRevision() {
        return new LauncherPack(id, name, description, icon, recommendedVersionId, retainedVersionIds,
                createdAt, System.currentTimeMillis(), revision + 1);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String name;
        private String description;
        private String icon;
        private String recommendedVersionId;
        private List<String> retainedVersionIds = List.of();
        private long createdAt;
        private long updatedAt;
        private long revision;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder recommendedVersionId(String v) {
            this.recommendedVersionId = v;
            return this;
        }

        public Builder retainedVersionIds(List<String> ids) {
            this.retainedVersionIds = ids == null ? List.of() : List.copyOf(ids);
            return this;
        }

        public Builder createdAt(long t) {
            this.createdAt = t;
            return this;
        }

        public Builder updatedAt(long t) {
            this.updatedAt = t;
            return this;
        }

        public Builder revision(long revision) {
            this.revision = revision;
            return this;
        }

        public LauncherPack build() {
            return new LauncherPack(id, name, description, icon, recommendedVersionId,
                    retainedVersionIds, createdAt, updatedAt, revision);
        }
    }
}
