package online.yudream.base.plugin.apprelease.application;

import online.yudream.base.plugin.apprelease.domain.AppRelease;
import online.yudream.base.plugin.apprelease.domain.ReleaseRepository;
import online.yudream.base.plugin.apprelease.domain.ReleaseSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 更新发布服务用例：上传/覆盖、发布流转、检查与强制更新判定、设置与删除。
 */
class ReleaseServiceTest {

    private InMemoryReleaseRepository repository;
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryReleaseRepository();
        service = new ReleaseService(repository);
    }

    @Test
    void createRequiresContentAndFileName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("android", 2, "0.2.0", "", false, "", new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("android", 2, "0.2.0", "", false, "app.apk", new byte[0]));
    }

    @Test
    void createStoresPackageAndStartsUnpublished() {
        AppRelease release = upload(2, "0.2.0", false);
        assertTrue(repository.packages.containsKey(release.id()));
        assertFalse(release.published());
        assertEquals(2, release.versionCode());
        assertEquals("0.2.0", release.versionName());
        assertEquals("android", release.platform());
        assertEquals(64, release.sha256().length());
    }

    @Test
    void sameVersionCodeReplacesPreviousPackage() {
        AppRelease first = upload(2, "0.2.0", false);
        AppRelease second = upload(2, "0.2.0", false);
        assertEquals(1, repository.releases.size());
        assertTrue(repository.releases.stream().noneMatch(r -> r.id().equals(first.id())));
        assertEquals(second.id(), repository.releases.get(0).id());
        assertFalse(repository.packages.containsKey(first.id()));
    }

    @Test
    void publishUnpublishAndDeleteFlow() {
        AppRelease release = upload(2, "0.2.0", false);
        service.publish(release.id());
        assertTrue(service.latest("android").isPresent());
        service.unpublish(release.id());
        assertTrue(service.latest("android").isEmpty());
        service.publish(release.id());
        service.delete(release.id());
        assertTrue(service.latest("android").isEmpty());
        assertFalse(repository.packages.containsKey(release.id()));
        assertThrows(IllegalArgumentException.class, () -> service.publish(release.id()));
    }

    @Test
    void checkWithoutReleaseIsNeverAvailable() {
        Map<String, Object> body = service.check("android", 1);
        assertEquals(Boolean.FALSE, body.get("updateAvailable"));
        assertEquals(Boolean.FALSE, body.get("forced"));
    }

    @Test
    void newerLatestSuggestsOptionalUpdate() {
        upload(2, "0.2.0", false);
        service.publish(forceIdOf(2));
        Map<String, Object> body = service.check("android", 1);
        assertEquals(Boolean.TRUE, body.get("updateAvailable"));
        assertEquals(Boolean.FALSE, body.get("forced"));

        Map<String, Object> same = service.check("android", 2);
        assertEquals(Boolean.FALSE, same.get("updateAvailable"));
        assertEquals(Boolean.FALSE, same.get("forced"));
    }

    @Test
    void forceUpdateFlagForcesOlderClients() {
        upload(2, "0.2.0", true);
        service.publish(forceIdOf(2));
        assertEquals(Boolean.TRUE, service.check("android", 1).get("forced"));
        // 已是最新版的客户端不被强制
        assertEquals(Boolean.FALSE, service.check("android", 2).get("forced"));
    }

    @Test
    void minVersionCodeForcesWithoutNewReleaseFlag() {
        upload(2, "0.2.0", false);
        service.publish(forceIdOf(2));
        service.saveSettings(99);
        assertEquals(Boolean.TRUE, service.check("android", 1).get("forced"));
        // minVersionCode 高于全部已发布版本时不能把无更新可装的客户端锁死
        assertEquals(Boolean.FALSE, service.check("android", 200).get("forced"));
    }

    @Test
    void platformIsolated() {
        upload(2, "0.2.0", false);
        service.publish(forceIdOf(2));
        assertTrue(service.latest("ios").isEmpty());
        assertEquals(Boolean.FALSE, service.check("IOS", 1).get("updateAvailable"));
    }

    @Test
    void settingsRejectNegative() {
        assertThrows(IllegalArgumentException.class, () -> service.saveSettings(-1));
        service.saveSettings(5);
        assertEquals(5, service.settings().minVersionCode());
    }

    private AppRelease upload(int versionCode, String versionName, boolean forceUpdate) {
        return service.create("android", versionCode, versionName, "changelog " + versionName,
                forceUpdate, "ydam-" + versionName + ".apk", ("bin-" + versionCode).getBytes(StandardCharsets.UTF_8));
    }

    private String forceIdOf(int versionCode) {
        return service.listAll().stream()
                .filter(r -> r.versionCode() == versionCode)
                .findFirst()
                .orElseThrow()
                .id();
    }

    /** 纯内存仓储桩：元数据与二进制分簿，模拟 PluginFileStore 语义。 */
    private static class InMemoryReleaseRepository implements ReleaseRepository {
        final List<AppRelease> releases = new ArrayList<>();
        final Map<String, byte[]> packages = new LinkedHashMap<>();
        ReleaseSettings settings = ReleaseSettings.defaults();

        @Override
        public List<AppRelease> listAll() {
            return new ArrayList<>(releases);
        }

        @Override
        public Optional<AppRelease> find(String id) {
            return releases.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public void savePackage(AppRelease release, byte[] content) {
            packages.put(release.id(), content.clone());
            upsert(release);
        }

        @Override
        public void saveMeta(AppRelease release) {
            upsert(release);
        }

        @Override
        public void delete(String id) {
            releases.removeIf(r -> r.id().equals(id));
            packages.remove(id);
        }

        @Override
        public Optional<byte[]> readPackage(String id) {
            return Optional.ofNullable(packages.get(id));
        }

        @Override
        public ReleaseSettings settings() {
            return settings;
        }

        @Override
        public void saveSettings(ReleaseSettings value) {
            this.settings = value;
        }

        private void upsert(AppRelease release) {
            releases.removeIf(r -> r.id().equals(release.id()));
            releases.add(release);
        }
    }
}
