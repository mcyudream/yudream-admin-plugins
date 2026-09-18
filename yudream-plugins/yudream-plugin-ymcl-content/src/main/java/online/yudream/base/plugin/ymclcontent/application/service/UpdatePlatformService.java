package online.yudream.base.plugin.ymclcontent.application.service;

import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.ymclcontent.domain.UpdateArtifact;
import online.yudream.base.plugin.ymclcontent.domain.UpdateRelease;
import online.yudream.base.plugin.ymclcontent.interfaces.support.UpdateHttpSupport;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * YMCL 自有更新平台：发布元数据 + 制品（外部 URL / 站内文件双模式）。
 * 数据落在 DocumentStore；站内二进制落在 PluginFileStore。
 */
public class UpdatePlatformService {

    public static final String RELEASE_COLLECTION = "ymcl_update_releases";
    public static final String FILE_PREFIX = "ymcl-update/files/";

    private final PluginDocumentStore documents;
    private final PluginFileStore files;

    public UpdatePlatformService(PluginDocumentStore documents, PluginFileStore files) {
        this.documents = documents;
        this.files = files;
    }

    public List<UpdateRelease> listReleases() {
        List<UpdateRelease> releases = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(RELEASE_COLLECTION, page, 200);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> doc : batch) {
                releases.add(UpdateRelease.fromMap(doc));
            }
            if (batch.size() < 200) {
                break;
            }
            page++;
        }
        releases.sort(UpdatePlatformService::compareReleasesDesc);
        return releases;
    }

    public Optional<UpdateRelease> findRelease(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(RELEASE_COLLECTION, version).map(UpdateRelease::fromMap);
    }

    /** 渠道内最新且公开可见的发布。 */
    public Optional<UpdateRelease> latestForChannel(String channel) {
        String normalized = UpdateHttpSupport.normalizeChannel(channel);
        return listReleases().stream()
                .filter(UpdateRelease::isPubliclyVisible)
                .filter(release -> UpdateHttpSupport.matchesChannel(normalized, release.version()))
                .max(UpdatePlatformService::compareReleasesAsc);
    }

    public UpdateRelease saveRelease(Map<String, Object> payload) {
        String version = text(payload.get("version"));
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        version = version.trim();
        if (version.contains("/") || version.contains("..") || version.contains("\\")) {
            throw new IllegalArgumentException("unsafe version");
        }
        Optional<UpdateRelease> existing = findRelease(version);
        List<UpdateArtifact> artifacts = existing.map(UpdateRelease::artifacts)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
        if (payload.containsKey("artifacts") && payload.get("artifacts") instanceof List<?> raw) {
            // 整表覆盖 artifacts 仅允许管理端显式传入；启动器写路径不走这里。
            artifacts = UpdateArtifact.listFromMaps(raw);
        }

        String now = String.valueOf(System.currentTimeMillis());
        String channel = text(payload.get("channel"));
        if (channel == null || channel.isBlank()) {
            channel = UpdateRelease.resolveChannel(version);
        }
        String publishedAt = text(payload.get("publishedAt"));
        if (publishedAt == null || publishedAt.isBlank()) {
            publishedAt = existing.map(UpdateRelease::publishedAt).orElse(now);
        }
        String title = text(payload.get("title"));
        if (title == null || title.isBlank()) {
            title = existing.map(UpdateRelease::title).orElse(null);
        }
        String notes = text(payload.get("notes"));
        if (notes == null) {
            notes = existing.map(UpdateRelease::notes).orElse(null);
        }
        String externalUrl = text(payload.get("externalUrl"));
        if (externalUrl == null) {
            externalUrl = existing.map(UpdateRelease::externalUrl).orElse(null);
        }
        if (externalUrl != null && externalUrl.isBlank()) {
            externalUrl = null;
        }
        Map<String, List<String>> changes;
        if (payload.containsKey("changes")) {
            changes = UpdateRelease.changesFromMap(payload.get("changes"));
        } else {
            changes = existing.map(UpdateRelease::normalizedChanges).orElseGet(LinkedHashMap::new);
        }
        UpdateRelease release = new UpdateRelease(
                version,
                channel,
                title,
                notes,
                changes,
                publishedAt,
                bool(payload.get("forceUpdate")),
                existing.map(UpdateRelease::yanked).orElse(false),
                payload.get("enabled") == null || bool(payload.get("enabled")),
                externalUrl,
                artifacts,
                now);
        persist(release);
        return release;
    }

    public Optional<UpdateRelease> upsertUrlArtifact(String version, Map<String, Object> payload) {
        Optional<UpdateRelease> existing = findRelease(version);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        String downloadUrl = text(payload.get("downloadUrl"));
        if (downloadUrl == null || !(downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("downloadUrl must be an absolute http(s) URL");
        }
        UpdateArtifact artifact = buildArtifact(payload, downloadUrl, null, text(payload.get("filename")), null);
        return Optional.of(replaceArtifact(existing.get(), artifact));
    }

    public Optional<UpdateRelease> upsertFileArtifact(String version, Map<String, Object> payload,
            byte[] bytes, String filename) {
        Optional<UpdateRelease> existing = findRelease(version);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("artifact file is empty");
        }
        String safeName = sanitizeFilename(filename != null && !filename.isBlank()
                ? filename
                : text(payload.get("filename")));
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        String fileKey = FILE_PREFIX + sanitizeFilename(version) + "/" + safeName;
        files.put(fileKey, new ByteArrayInputStream(bytes), (long) bytes.length,
                contentTypeOf(safeName, text(payload.get("contentType"))));
        String sha256 = text(payload.get("sha256"));
        if (sha256 == null || sha256.isBlank()) {
            sha256 = sha256Hex(bytes);
        }
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        normalized.put("sha256", sha256);
        normalized.put("size", bytes.length);
        UpdateArtifact artifact = buildArtifact(normalized, null, fileKey, safeName,
                contentTypeOf(safeName, text(payload.get("contentType"))));
        return Optional.of(replaceArtifact(existing.get(), artifact));
    }

    public Optional<UpdateRelease> removeArtifact(String version, String artifactId) {
        Optional<UpdateRelease> existing = findRelease(version);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        UpdateRelease release = existing.get();
        List<UpdateArtifact> next = new ArrayList<>();
        boolean removed = false;
        for (UpdateArtifact artifact : release.artifacts()) {
            if (artifact.id() != null && artifact.id().equals(artifactId)) {
                removed = true;
                if (artifact.hostedLocally()) {
                    try {
                        files.delete(artifact.fileKey());
                    } catch (Exception ignored) {
                        // 文件可能已不存在，忽略。
                    }
                }
                continue;
            }
            next.add(artifact);
        }
        if (!removed) {
            return Optional.empty();
        }
        UpdateRelease updated = withArtifacts(release, next);
        persist(updated);
        return Optional.of(updated);
    }

    public Optional<UpdateRelease> setYanked(String version, boolean yanked) {
        Optional<UpdateRelease> existing = findRelease(version);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        UpdateRelease release = existing.get();
        UpdateRelease updated = new UpdateRelease(
                release.version(),
                release.channel(),
                release.title(),
                release.notes(),
                release.changes(),
                release.publishedAt(),
                release.forceUpdate(),
                yanked,
                release.enabled(),
                release.externalUrl(),
                release.artifacts(),
                String.valueOf(System.currentTimeMillis()));
        persist(updated);
        return Optional.of(updated);
    }

    public boolean deleteRelease(String version) {
        Optional<UpdateRelease> existing = findRelease(version);
        if (existing.isEmpty()) {
            return false;
        }
        for (UpdateArtifact artifact : existing.get().artifacts()) {
            if (artifact.hostedLocally()) {
                try {
                    files.delete(artifact.fileKey());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        documents.delete(RELEASE_COLLECTION, version);
        return true;
    }

    public PluginStoredFile readArtifactFile(String version, String filename) {
        String safeVersion = sanitizeFilename(version);
        String safeName = sanitizeFilename(filename);
        if (safeVersion.isBlank() || safeName.isBlank()) {
            return null;
        }
        Optional<UpdateRelease> release = findRelease(safeVersion);
        if (release.isEmpty()) {
            return null;
        }
        String fileKey = FILE_PREFIX + safeVersion + "/" + safeName;
        for (UpdateArtifact artifact : release.get().artifacts()) {
            if (!fileKey.equals(artifact.fileKey())) {
                continue;
            }
            try {
                return files.get(artifact.fileKey());
            } catch (Exception miss) {
                return null;
            }
        }
        return null;
    }

    /** 对外下发：解析制品下载 URL（站内文件 → origin + path；外部 → 原 URL）。 */
    public String resolveDownloadUrl(String origin, UpdateRelease release, UpdateArtifact artifact) {
        if (artifact.hasExternalUrl()) {
            return artifact.downloadUrl();
        }
        if (artifact.hostedLocally() && artifact.filename() != null && !artifact.filename().isBlank()) {
            return UpdateHttpSupport.absoluteUpdateFileUrl(origin, release.version(), artifact.filename());
        }
        return null;
    }

    public String resolveRelativePath(UpdateRelease release, UpdateArtifact artifact) {
        if (artifact.hostedLocally() && artifact.filename() != null && !artifact.filename().isBlank()) {
            return UpdateHttpSupport.relativeUpdateFilePath(release.version(), artifact.filename());
        }
        if (artifact.hasExternalUrl()) {
            return artifact.downloadUrl();
        }
        return null;
    }

    private void persist(UpdateRelease release) {
        Map<String, Object> doc = release.toAdminRecord();
        doc.put("version", release.version());
        documents.save(RELEASE_COLLECTION, release.version(), doc);
    }

    private UpdateRelease replaceArtifact(UpdateRelease release, UpdateArtifact next) {
        List<UpdateArtifact> artifacts = new ArrayList<>();
        boolean replaced = false;
        for (UpdateArtifact artifact : release.artifacts()) {
            boolean sameIdentity = sameArtifactIdentity(artifact, next);
            if (sameIdentity) {
                // 替换同 kind/platform/arch/variant 的旧制品
                if (artifact.hostedLocally() && !artifact.fileKey().equals(next.fileKey())) {
                    try {
                        files.delete(artifact.fileKey());
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
                artifacts.add(next);
                replaced = true;
                continue;
            }
            if (next.id() != null && next.id().equals(artifact.id())) {
                artifacts.add(next);
                replaced = true;
                continue;
            }
            artifacts.add(artifact);
        }
        if (!replaced) {
            artifacts.add(next);
        }
        UpdateRelease updated = withArtifacts(release, artifacts);
        persist(updated);
        return updated;
    }

    private static UpdateRelease withArtifacts(UpdateRelease release, List<UpdateArtifact> artifacts) {
        return new UpdateRelease(
                release.version(),
                release.channel(),
                release.title(),
                release.notes(),
                release.changes(),
                release.publishedAt(),
                release.forceUpdate(),
                release.yanked(),
                release.enabled(),
                release.externalUrl(),
                artifacts,
                String.valueOf(System.currentTimeMillis()));
    }

    private static boolean sameArtifactIdentity(UpdateArtifact left, UpdateArtifact right) {
        return eq(left.kind(), right.kind())
                && eq(left.platform(), right.platform())
                && eq(left.architecture(), right.architecture())
                && eq(left.variant(), right.variant());
    }

    private static boolean eq(String left, String right) {
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        return l.equalsIgnoreCase(r);
    }

    private static UpdateArtifact buildArtifact(Map<String, Object> payload, String downloadUrl, String fileKey,
            String filename, String contentType) {
        String id = text(payload.get("id"));
        if (id == null || id.isBlank()) {
            id = "art-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        String kind = text(payload.get("kind"));
        if (kind == null || kind.isBlank()) {
            kind = UpdateArtifact.KIND_INSTALLER;
        }
        List<String> targets = new ArrayList<>();
        Object rawTargets = payload.get("targetPlatforms");
        if (rawTargets instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null && !String.valueOf(entry).isBlank()) {
                    targets.add(String.valueOf(entry));
                }
            }
        } else {
            String single = text(payload.get("platformId"));
            if (single != null && !single.isBlank()) {
                targets.add(single);
            }
        }
        return new UpdateArtifact(
                id,
                kind,
                text(payload.get("variant")),
                text(payload.get("platform")),
                text(payload.get("architecture")),
                targets,
                filename,
                downloadUrl,
                fileKey,
                text(payload.get("sha256")),
                longOf(payload.get("size")),
                text(payload.get("signature")),
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                String.valueOf(System.currentTimeMillis()));
    }

    /** multipart form 文本字段。 */
    public static String partText(Map<String, PluginHttpPart> parts, String name) {
        if (parts == null) {
            return null;
        }
        PluginHttpPart part = parts.get(name);
        if (part == null || part.data() == null || part.data().length == 0) {
            return null;
        }
        return new String(part.data(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    public static String sanitizeFilename(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replace("..", "_");
        return value.trim();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static byte[] decodeBase64(String data) {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("data is required");
        }
        try {
            return Base64.getDecoder().decode(data.replaceAll("\\s", ""));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("data is not valid base64");
        }
    }

    private static String contentTypeOf(String filename, String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return "application/gzip";
        }
        if (lower.endsWith(".deb")) {
            return "application/vnd.debian.binary-package";
        }
        if (lower.endsWith(".rpm")) {
            return "application/x-rpm";
        }
        if (lower.endsWith(".exe")) {
            return "application/vnd.microsoft.portable-executable";
        }
        if (lower.endsWith(".dmg")) {
            return "application/x-apple-diskimage";
        }
        return "application/octet-stream";
    }

    private static int compareReleasesAsc(UpdateRelease left, UpdateRelease right) {
        int cmp = UpdateHttpSupport.isNewerVersion(left.version(), right.version())
                ? 1
                : UpdateHttpSupport.isNewerVersion(right.version(), left.version()) ? -1 : 0;
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(publishedAtMillis(left), publishedAtMillis(right));
    }

    private static int compareReleasesDesc(UpdateRelease left, UpdateRelease right) {
        return -compareReleasesAsc(left, right);
    }

    private static long publishedAtMillis(UpdateRelease release) {
        String raw = release.publishedAt();
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            try {
                return java.time.Instant.parse(raw.trim()).toEpochMilli();
            } catch (Exception ignoredAgain) {
                try {
                    return java.time.LocalDate.parse(raw.trim())
                            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                } catch (Exception ignoredFinal) {
                    return 0L;
                }
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
