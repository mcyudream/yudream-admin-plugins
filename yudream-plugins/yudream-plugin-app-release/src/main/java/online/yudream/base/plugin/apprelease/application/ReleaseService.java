package online.yudream.base.plugin.apprelease.application;

import online.yudream.base.plugin.apprelease.domain.AppRelease;
import online.yudream.base.plugin.apprelease.domain.ReleaseRepository;
import online.yudream.base.plugin.apprelease.domain.ReleaseSettings;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 更新发布应用服务：上传、发布/下架、删除、通道设置与客户端检查。
 * 强制更新判定：存在更高版本且（客户端低于强制线 minVersionCode 或最新版标记 forceUpdate）。
 */
public class ReleaseService {

    private final ReleaseRepository repository;

    public ReleaseService(ReleaseRepository repository) {
        this.repository = repository;
    }

    /** 上传新版本包：同平台同 versionCode 视为重新发包，覆盖旧记录。 */
    public AppRelease create(String platform, int versionCode, String versionName, String changelog,
                             boolean forceUpdate, String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("更新包文件不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("更新包文件名不能为空");
        }
        String normalized = platform == null || platform.isBlank() ? "android" : platform;
        repository.listAll().stream()
                .filter(r -> r.platform().equals(normalized.trim().toLowerCase()) && r.versionCode() == versionCode)
                .findFirst()
                .ifPresent(previous -> repository.delete(previous.id()));
        AppRelease release = new AppRelease(newId(), normalized, versionCode, versionName, changelog,
                fileName.trim(), content.length, sha256Hex(content), forceUpdate, false,
                System.currentTimeMillis(), 0L);
        repository.savePackage(release, content);
        return release;
    }

    public AppRelease publish(String id) {
        AppRelease release = require(id);
        AppRelease published = release.withPublished(true);
        repository.saveMeta(published);
        return published;
    }

    public AppRelease unpublish(String id) {
        AppRelease release = require(id);
        AppRelease unpublished = release.withPublished(false);
        repository.saveMeta(unpublished);
        return unpublished;
    }

    public void delete(String id) {
        require(id);
        repository.delete(id);
    }

    /** 读取更新包二进制；不存在返回 empty。 */
    public Optional<byte[]> readPackage(String id) {
        return repository.readPackage(id);
    }

    public List<AppRelease> listAll() {
        return repository.listAll();
    }

    public List<AppRelease> listPublished(String platform) {
        String normalized = normalizePlatform(platform);
        return repository.listAll().stream()
                .filter(AppRelease::published)
                .filter(r -> r.platform().equals(normalized))
                .toList();
    }

    public Optional<AppRelease> latest(String platform) {
        return listPublished(platform).stream().findFirst();
    }

    public ReleaseSettings settings() {
        return repository.settings();
    }

    public ReleaseSettings saveSettings(int minVersionCode) {
        ReleaseSettings settings = new ReleaseSettings(minVersionCode, System.currentTimeMillis());
        repository.saveSettings(settings);
        return settings;
    }

    /** 客户端检查结果（供接口层直接序列化）。 */
    public Map<String, Object> check(String platform, int clientVersionCode) {
        String normalized = normalizePlatform(platform);
        ReleaseSettings settings = repository.settings();
        Optional<AppRelease> latest = latest(normalized);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", normalized);
        body.put("clientVersionCode", clientVersionCode);
        body.put("minVersionCode", settings.minVersionCode());
        if (latest.isEmpty()) {
            body.put("updateAvailable", false);
            body.put("forced", false);
            body.put("latest", null);
            return body;
        }
        AppRelease top = latest.get();
        boolean updateAvailable = clientVersionCode < top.versionCode();
        boolean forced = updateAvailable
                && (clientVersionCode < settings.minVersionCode() || top.forceUpdate());
        body.put("updateAvailable", updateAvailable);
        body.put("forced", forced);
        body.put("latest", releaseView(top, true));
        return body;
    }

    /** 版本视图（管理端与公开端共用；公开端不暴露 sha256）。 */
    public Map<String, Object> releaseView(AppRelease release, boolean withSha) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", release.id());
        view.put("platform", release.platform());
        view.put("versionCode", release.versionCode());
        view.put("versionName", release.versionName());
        view.put("changelog", release.changelog());
        view.put("fileName", release.fileName());
        view.put("fileSize", release.fileSize());
        if (withSha) {
            view.put("sha256", release.sha256());
        }
        view.put("forceUpdate", release.forceUpdate());
        view.put("published", release.published());
        view.put("createdAt", release.createdAt());
        view.put("publishedAt", release.publishedAt());
        return view;
    }

    private AppRelease require(String id) {
        return repository.find(id).orElseThrow(() -> new IllegalArgumentException("版本包不存在或已删除"));
    }

    private String normalizePlatform(String platform) {
        return platform == null || platform.isBlank() ? "android" : platform.trim().toLowerCase();
    }

    private String newId() {
        String random = Integer.toHexString((int) (System.nanoTime() & 0xffffff));
        return "rel-" + Long.toString(System.currentTimeMillis(), 36) + "-" + random;
    }

    static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
