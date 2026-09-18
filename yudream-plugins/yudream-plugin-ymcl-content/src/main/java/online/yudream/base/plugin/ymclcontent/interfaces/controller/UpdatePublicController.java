package online.yudream.base.plugin.ymclcontent.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.ymclcontent.application.service.UpdatePlatformService;
import online.yudream.base.plugin.ymclcontent.domain.UpdateArtifact;
import online.yudream.base.plugin.ymclcontent.domain.UpdateRelease;
import online.yudream.base.plugin.ymclcontent.interfaces.support.UpdateHttpSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YMCL 自有更新平台公开端点（匿名，wrapResult=false）。
 *
 * 路径前缀：/api/plugins/ymcl-content/v1/update/**
 * 与 Axolotl update.axlmc.org 协议对齐，便于启动器切换 env base。
 */
public class UpdatePublicController {

    private static final int API_VERSION = 1;

    private final UpdatePlatformService updates;

    public UpdatePublicController(UpdatePlatformService updates) {
        this.updates = updates;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/update/capabilities", wrapResult = false)
    public PluginHttpResponse capabilities(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plugin", "ymcl-content");
        payload.put("feature", "launcher-update");
        payload.put("apiVersion", API_VERSION);
        payload.put("features", Map.of(
                "latest", true,
                "manifest", true,
                "versionsCatalog", true,
                "downloadsLatest", true,
                "hostedFiles", true,
                "history", true));
        payload.put("channels", List.of("release", "beta"));
        payload.put("platforms", List.of(
                "windows-x86_64",
                "linux-x86_64",
                "linux-aarch64",
                "darwin-x86_64",
                "darwin-aarch64"));
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * 启动器「更新历史 / 发布说明」列表。
     * Query：channel（可空=全部）、limit（默认 50，最大 200）
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/history", wrapResult = false)
    public PluginHttpResponse history(PluginHttpRequest request) {
        String channel = UpdateHttpSupport.firstQuery(request, "channel");
        int limit = 50;
        String rawLimit = UpdateHttpSupport.firstQuery(request, "limit");
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Math.max(1, Math.min(200, Integer.parseInt(rawLimit.trim())));
            } catch (NumberFormatException ignored) {
                limit = 50;
            }
        }
        List<Map<String, Object>> releases = new ArrayList<>();
        for (UpdateRelease release : updates.listReleases()) {
            if (!release.isPubliclyVisible()) {
                continue;
            }
            if (channel != null && !channel.isBlank()
                    && !UpdateHttpSupport.matchesChannel(channel, release.version())
                    && !eqIgnoreCase(release.channel(), channel)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "ymcl-" + release.version());
            item.put("version", release.version());
            item.put("channel", release.channel());
            item.put("title", release.resolvedTitle());
            item.put("publishedAt", release.publishedAt());
            item.put("forceUpdate", release.forceUpdate());
            item.put("notes", release.resolvedNotes());
            item.put("changes", release.normalizedChanges());
            item.put("externalUrl", release.externalUrl() == null ? "" : release.externalUrl());
            releases.add(item);
            if (releases.size() >= limit) {
                break;
            }
        }
        return PluginHttpResponse.rawJson(200, Map.of(
                "apiVersion", API_VERSION,
                "releases", releases,
                "total", releases.size()));
    }

    /** 渠道最新版本摘要：设置页渠道卡片 / 官网下载按钮。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/latest", wrapResult = false)
    public PluginHttpResponse latest(PluginHttpRequest request) {
        String channel = UpdateHttpSupport.queryOrHeader(request, "channel", "X-YMCL-Channel", "release");
        Optional<UpdateRelease> release = updates.latestForChannel(channel);
        if (release.isEmpty()) {
            return PluginHttpResponse.rawJson(200, Map.of(
                    "apiVersion", API_VERSION,
                    "channel", UpdateHttpSupport.normalizeChannel(channel),
                    "version", null));
        }
        UpdateRelease value = release.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiVersion", API_VERSION);
        payload.put("channel", UpdateHttpSupport.normalizeChannel(channel));
        payload.put("version", value.version());
        payload.put("title", value.resolvedTitle());
        payload.put("publishedAt", value.publishedAt());
        payload.put("forceUpdate", value.forceUpdate());
        payload.put("notes", value.resolvedNotes());
        payload.put("changes", value.normalizedChanges());
        payload.put("externalUrl", value.externalUrl() == null ? "" : value.externalUrl());
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * Tauri updater 兼容清单。
     * Headers：X-YMCL-Channel / X-YMCL-Platform / X-YMCL-Version
     * Query 等价：channel / platform / version
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/manifest", wrapResult = false)
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        String channel = UpdateHttpSupport.queryOrHeader(request, "channel", "X-YMCL-Channel", "release");
        String platform = UpdateHttpSupport.queryOrHeader(request, "platform", "X-YMCL-Platform", null);
        String currentVersion = UpdateHttpSupport.queryOrHeader(request, "version", "X-YMCL-Version", null);
        Optional<UpdateRelease> latest = updates.latestForChannel(channel);
        if (latest.isEmpty()) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "no published update",
                    "channel", UpdateHttpSupport.normalizeChannel(channel)));
        }
        UpdateRelease release = latest.get();
        if (currentVersion != null && !currentVersion.isBlank()
                && !UpdateHttpSupport.isNewerVersion(release.version(), currentVersion)) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "already up to date",
                    "version", release.version(),
                    "currentVersion", currentVersion));
        }

        String origin = UpdateHttpSupport.normalizeOrigin(request);
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (UpdateArtifact artifact : release.artifacts()) {
            if (!UpdateArtifact.KIND_UPDATER.equalsIgnoreCase(artifact.kind())) {
                continue;
            }
            String signature = artifact.signature();
            if (signature == null || signature.isBlank()) {
                continue;
            }
            String url = updates.resolveDownloadUrl(origin, release, artifact);
            if (url == null || url.isBlank()) {
                continue;
            }
            List<String> targets = artifact.targetPlatforms();
            if (targets == null || targets.isEmpty()) {
                String single = platformKey(artifact.platform(), artifact.architecture());
                if (single != null && (platform == null || platform.isBlank() || single.equals(platform))) {
                    platforms.put(single, Map.of("signature", signature, "url", url));
                }
                continue;
            }
            for (String target : targets) {
                if (platform != null && !platform.isBlank() && !target.equals(platform)) {
                    continue;
                }
                platforms.put(target, Map.of("signature", signature, "url", url));
            }
        }
        if (platforms.isEmpty() && (platform == null || platform.isBlank())) {
            // 无 platform 过滤且没有任何 updater 制品时，仍返回元数据，便于调试。
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "no signed updater artifact for platform",
                    "version", release.version()));
        }
        if (platform != null && !platform.isBlank() && !platforms.containsKey(platform)) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "no signed updater artifact for platform",
                    "platform", platform,
                    "version", release.version()));
        }

        String pubDate = toIso(release.publishedAt());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", release.version());
        payload.put("title", release.resolvedTitle());
        payload.put("notes", release.resolvedNotes());
        payload.put("changes", release.normalizedChanges());
        payload.put("pub_date", pubDate);
        payload.put("platforms", platforms);
        payload.put("published_at", pubDate);
        payload.put("force_update", release.forceUpdate());
        payload.put("channel", release.channel());
        if (release.externalUrl() != null && !release.externalUrl().isBlank()) {
            payload.put("externalUrl", release.externalUrl());
        }
        return PluginHttpResponse.rawJson(200, payload);
    }

    /** 制品目录：兼容 update.axlmc.org /api/versions，供 apt/deb 等校验下载。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/versions", wrapResult = false)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        String origin = UpdateHttpSupport.normalizeOrigin(request);
        String channel = UpdateHttpSupport.firstQuery(request, "channel");
        List<Map<String, Object>> versions = new ArrayList<>();
        for (UpdateRelease release : updates.listReleases()) {
            if (!release.isPubliclyVisible()) {
                continue;
            }
            if (channel != null && !channel.isBlank()
                    && !UpdateHttpSupport.matchesChannel(channel, release.version())
                    && !eqIgnoreCase(release.channel(), channel)) {
                continue;
            }
            List<Map<String, Object>> artifacts = new ArrayList<>();
            for (UpdateArtifact artifact : release.artifacts()) {
                if (UpdateArtifact.KIND_SIGNATURE.equalsIgnoreCase(artifact.kind())) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", artifact.kind() == null ? "installer" : artifact.kind());
                entry.put("variant", artifact.variant());
                entry.put("platform", artifact.platform());
                entry.put("architecture", artifact.architecture());
                String relative = updates.resolveRelativePath(release, artifact);
                entry.put("relative_path", relative == null ? "" : relative);
                String absolute = updates.resolveDownloadUrl(origin, release, artifact);
                if (absolute != null) {
                    entry.put("url", absolute);
                }
                if (artifact.sha256() != null && !artifact.sha256().isBlank()) {
                    entry.put("sha256", artifact.sha256());
                }
                entry.put("size", artifact.size());
                if (artifact.filename() != null) {
                    entry.put("filename", artifact.filename());
                }
                artifacts.add(entry);
            }
            versions.add(Map.of(
                    "version", release.version(),
                    "channel", release.channel(),
                    "artifacts", artifacts));
        }
        return PluginHttpResponse.rawJson(200, Map.of(
                "apiVersion", API_VERSION,
                "versions", versions));
    }

    /** 官网/下载区：渠道最新包元数据。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/downloads/latest", wrapResult = false)
    public PluginHttpResponse downloadsLatest(PluginHttpRequest request) {
        String channel = UpdateHttpSupport.queryOrHeader(request, "channel", "X-YMCL-Channel", "release");
        Optional<UpdateRelease> latest = updates.latestForChannel(channel);
        if (latest.isEmpty()) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "no published update",
                    "channel", UpdateHttpSupport.normalizeChannel(channel)));
        }
        UpdateRelease release = latest.get();
        String origin = UpdateHttpSupport.normalizeOrigin(request);
        List<Map<String, Object>> downloads = new ArrayList<>();
        for (UpdateArtifact artifact : release.artifacts()) {
            if (UpdateArtifact.KIND_SIGNATURE.equalsIgnoreCase(artifact.kind())) {
                continue;
            }
            String url = updates.resolveDownloadUrl(origin, release, artifact);
            if (url == null || url.isBlank() || artifact.filename() == null || artifact.filename().isBlank()) {
                continue;
            }
            downloads.add(Map.of(
                    "filename", artifact.filename(),
                    "url", url,
                    "kind", artifact.kind() == null ? "installer" : artifact.kind(),
                    "platform", artifact.platform() == null ? "" : artifact.platform(),
                    "architecture", artifact.architecture() == null ? "" : artifact.architecture()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiVersion", API_VERSION);
        payload.put("version", release.version());
        payload.put("title", release.resolvedTitle());
        payload.put("channel", release.channel());
        payload.put("publishedAt", release.publishedAt());
        payload.put("changes", release.normalizedChanges());
        payload.put("downloads", downloads);
        return PluginHttpResponse.rawJson(200, payload);
    }

    /** 站内托管更新包下载。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/update/files/{version}/{filename}", wrapResult = false)
    public PluginHttpResponse downloadFile(PluginHttpRequest request) {
        String version = UpdateHttpSupport.segmentAfter(request.path(), "files", 0);
        String filename = UpdateHttpSupport.segmentAfter(request.path(), "files", 1);
        PluginStoredFile stored = updates.readArtifactFile(version, filename);
        if (stored == null) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "update artifact not found",
                    "version", version == null ? "" : version,
                    "filename", filename == null ? "" : filename));
        }
        try (var input = stored.inputStream()) {
            byte[] bytes = input.readAllBytes();
            String safeName = UpdatePlatformService.sanitizeFilename(filename);
            return new PluginHttpResponse(
                    200,
                    Map.of(
                            "Cache-Control", "public, max-age=3600",
                            "Content-Disposition", "attachment; filename=\"" + safeName + "\""),
                    "application/octet-stream",
                    bytes,
                    false);
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(500, Map.of(
                    "message", "failed to read update artifact",
                    "error", String.valueOf(error.getMessage())));
        }
    }

    private static String platformKey(String platform, String architecture) {
        if (platform == null || architecture == null) {
            return null;
        }
        return platform.trim().toLowerCase() + "-" + architecture.trim().toLowerCase();
    }

    private static boolean eqIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static String toIso(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) {
            return java.time.Instant.now().toString();
        }
        String value = publishedAt.trim();
        try {
            long millis = Long.parseLong(value);
            return java.time.Instant.ofEpochMilli(millis).toString();
        } catch (NumberFormatException ignored) {
            try {
                return java.time.Instant.parse(value).toString();
            } catch (Exception ignoredAgain) {
                try {
                    return java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC)
                            .toInstant().toString();
                } catch (Exception ignoredFinal) {
                    return value;
                }
            }
        }
    }
}
