package online.yudream.base.plugin.launcher.domain.repo;

import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPackVersion;

import java.util.List;
import java.util.Optional;

public interface PackRepository {

    List<LauncherPack> listPacks();

    Optional<LauncherPack> findPack(String packId);

    void savePack(LauncherPack pack);

    List<LauncherPackVersion> listVersions(String packId);

    Optional<LauncherPackVersion> findVersion(String packId, String versionId);

    void saveVersion(LauncherPackVersion version);
}
