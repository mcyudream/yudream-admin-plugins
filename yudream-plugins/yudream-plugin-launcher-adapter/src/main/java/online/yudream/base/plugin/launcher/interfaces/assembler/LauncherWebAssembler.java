package online.yudream.base.plugin.launcher.interfaces.assembler;

import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPackVersion;
import online.yudream.base.plugin.launcher.interfaces.res.PackDetailRes;
import online.yudream.base.plugin.launcher.interfaces.res.PackRes;
import online.yudream.base.plugin.launcher.interfaces.res.PackVersionRes;

import java.util.List;

public class LauncherWebAssembler {

    public PackRes toPackRes(LauncherPack pack) {
        return new PackRes(
                pack.id(),
                pack.name(),
                pack.description(),
                pack.icon(),
                pack.recommendedVersionId(),
                pack.retainedVersionIds(),
                String.valueOf(pack.createdAt()),
                String.valueOf(pack.updatedAt())
        );
    }

    public PackVersionRes toVersionRes(LauncherPackVersion version) {
        return new PackVersionRes(
                version.packId(),
                version.versionId(),
                version.indexHash(),
                version.overridesObjectKey(),
                version.downloads(),
                version.changelog(),
                String.valueOf(version.publishedAt()),
                version.publisherUserId() == null ? null : String.valueOf(version.publisherUserId()),
                version.status() == null ? null : version.status().name()
        );
    }

    public PackDetailRes toDetailRes(LauncherPack pack, List<LauncherPackVersion> versions) {
        return new PackDetailRes(toPackRes(pack), versions.stream().map(this::toVersionRes).toList());
    }
}
