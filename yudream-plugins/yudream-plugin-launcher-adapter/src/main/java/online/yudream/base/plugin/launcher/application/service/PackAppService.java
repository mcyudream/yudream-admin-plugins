package online.yudream.base.plugin.launcher.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPackVersion;
import online.yudream.base.plugin.launcher.domain.repo.PackRepository;
import online.yudream.base.plugin.launcher.domain.valobj.LauncherEntry;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 整合包应用服务。负责 pack 元数据与版本的业务规则（创建/推送/回滚），管理下载白名单，
 * 并在发布/覆盖物变更时物化协议 v2 §5 的对账 entries（url 条目来自 mrpack index，
 * managed 条目来自 overrides zip，按 sha1 内容寻址入文件存储）。
 */
public class PackAppService {

    private static final int DEFAULT_KEEP_LATEST_VERSIONS = 10;
    static final Set<String> DEFAULT_DOWNLOAD_HOSTS = Set.of(
            "cdn.modrinth.com",
            "edge.forgecdn.net",
            "media.forgecdn.net",
            "github.com",
            "raw.githubusercontent.com"
    );
    private static final Set<String> KNOWN_LOADERS = Set.of("vanilla", "fabric", "forge", "neoforge", "quilt");

    /**
     * 上传 overrides zip 的最大字节数（解码后）。
     * 200 MB 对典型 50 MB overrides 已足够；超出一律拒绝以免撑爆对象存储。
     */
    private static final long MAX_OVERRIDES_BYTES = 200L * 1024L * 1024L;

    private final PackRepository repository;
    private final PluginFileStore fileStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public PackAppService(PackRepository repository, PluginFileStore fileStore) {
        this.repository = repository;
        this.fileStore = fileStore;
    }

    public List<LauncherPack> listPacks() {
        return repository.listPacks();
    }

    public PackQueryResult queryPacks(String keyword, int page, int size) {
        List<LauncherPack> all = new ArrayList<>(repository.listPacks());
        all.sort((left, right) -> Long.compare(right.updatedAt(), left.updatedAt()));
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<LauncherPack> filtered = all;
        if (!query.isEmpty()) {
            filtered = all.stream()
                    .filter(pack -> pack.id().toLowerCase(Locale.ROOT).contains(query)
                            || pack.name().toLowerCase(Locale.ROOT).contains(query)
                            || pack.description().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
        }
        int safeSize = Math.max(1, Math.min(100, size));
        int safePage = Math.max(1, page);
        int total = filtered.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new PackQueryResult(filtered.subList(from, to), total);
    }

    public record PackQueryResult(List<LauncherPack> records, int total) {
    }

    public LauncherPack requirePack(String packId) {
        return repository.findPack(packId)
                .orElseThrow(() -> new IllegalArgumentException("pack 不存在: " + packId));
    }

    public LauncherPack createPack(String packId, String name, String description, String icon) {
        if (repository.findPack(packId).isPresent()) {
            throw new IllegalStateException("pack 已存在: " + packId);
        }
        long now = System.currentTimeMillis();
        LauncherPack pack = LauncherPack.builder()
                .id(packId)
                .name(name)
                .description(description)
                .icon(icon)
                .retainedVersionIds(List.of())
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.savePack(pack);
        return pack;
    }

    public LauncherPackVersion publishVersion(String packId, String versionId, String indexJson, String indexHash,
                                              String overridesObjectKey, List<String> downloads, String changelog,
                                              String publisherUserId) {
        validateIndex(indexJson);
        validateIndexHash(indexHash);
        validateDownloads(downloads);
        LauncherPack pack = requirePack(packId);
        if (repository.findVersion(packId, versionId).isPresent()) {
            throw new IllegalStateException("version 已存在: " + packId + "@" + versionId);
        }
        Long publisher = parseUserId(publisherUserId);
        List<LauncherEntry> urlEntries = parseUrlEntries(indexJson);
        GameInfo game = parseGameInfo(indexJson);
        List<LauncherEntry> entries = urlEntries;
        String storedOverridesKey = overridesObjectKey == null ? "" : overridesObjectKey;
        if (!storedOverridesKey.isBlank()) {
            entries = mergeEntries(urlEntries, materializeManagedEntries(readStoredBytes(storedOverridesKey)));
        }
        LauncherPack bumped = pack.bumpRevision();
        LauncherPackVersion version = LauncherPackVersion.publishMrpack(
                packId, versionId, indexJson, indexHash, storedOverridesKey, downloads, changelog, publisher,
                game.gameVersion(), game.loader(), game.loaderVersion(), bumped.revision(), entries,
                LauncherPackVersion.DEFAULT_IGNORE_PATTERNS);
        repository.saveVersion(version);
        repository.savePack(bumped
                .withRecommendedVersion(versionId)
                .retainVersion(versionId, DEFAULT_KEEP_LATEST_VERSIONS));
        return version;
    }

    /**
     * 发布 VANILLA 渠道版本（§5.3/§5.5）：只声明 gameVersion/loader/loaderVersion，
     * 可选附 overrides.zip（物化为 managed entries，仍走同一套 /files/{sha1} 对账）。
     */
    public LauncherPackVersion publishVanillaVersion(String packId, String versionId, String gameVersion,
                                                     String loader, String loaderVersion, String changelog,
                                                     List<String> ignorePatterns, String overridesContentBase64,
                                                     String expectedSha256, String publisherUserId) {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new IllegalArgumentException("gameVersion 不能为空");
        }
        String normalizedLoader = loader == null || loader.isBlank() ? "vanilla" : loader.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_LOADERS.contains(normalizedLoader)) {
            throw new IllegalArgumentException("未知 loader: " + loader + "（支持 " + KNOWN_LOADERS + "）");
        }
        String normalizedLoaderVersion = loaderVersion == null ? "" : loaderVersion.trim();
        if ("vanilla".equals(normalizedLoader) && !normalizedLoaderVersion.isBlank()) {
            throw new IllegalArgumentException("vanilla 渠道不得声明 loaderVersion");
        }
        if (!"vanilla".equals(normalizedLoader) && normalizedLoaderVersion.isBlank()) {
            throw new IllegalArgumentException("loader " + normalizedLoader + " 必须声明 loaderVersion");
        }
        LauncherPack pack = requirePack(packId);
        if (repository.findVersion(packId, versionId).isPresent()) {
            throw new IllegalStateException("version 已存在: " + packId + "@" + versionId);
        }
        Long publisher = parseUserId(publisherUserId);
        List<LauncherEntry> entries = List.of();
        String overridesKey = "";
        if (overridesContentBase64 != null && !overridesContentBase64.isBlank()) {
            byte[] zipBytes = decodeOverridesZip(overridesContentBase64, expectedSha256);
            overridesKey = overridesKey(packId, versionId);
            fileStore.put(overridesKey, new ByteArrayInputStream(zipBytes), zipBytes.length, "application/zip");
            entries = materializeManagedEntries(zipBytes);
        }
        LauncherPack bumped = pack.bumpRevision();
        LauncherPackVersion version = LauncherPackVersion.publishVanilla(
                packId, versionId, gameVersion.trim(), normalizedLoader, normalizedLoaderVersion, changelog,
                publisher, bumped.revision(), entries,
                ignorePatterns == null || ignorePatterns.isEmpty()
                        ? LauncherPackVersion.DEFAULT_IGNORE_PATTERNS : ignorePatterns);
        if (!overridesKey.isBlank()) {
            version = version.withOverrides(overridesKey, entries, version.revision());
        }
        repository.saveVersion(version);
        repository.savePack(bumped
                .withRecommendedVersion(versionId)
                .retainVersion(versionId, DEFAULT_KEEP_LATEST_VERSIONS));
        return version;
    }

    public LauncherPack rollback(String packId, String targetVersionId) {
        LauncherPack pack = requirePack(packId);
        if (!pack.retainedVersionIds().contains(targetVersionId)) {
            throw new IllegalArgumentException("目标版本不存在或不在保留列表中: " + targetVersionId);
        }
        LauncherPack next = pack.withRecommendedVersion(targetVersionId);
        repository.savePack(next);
        return next;
    }

    public List<LauncherPackVersion> listVersions(String packId) {
        requirePack(packId);
        return repository.listVersions(packId);
    }

    public LauncherPackVersion requireVersion(String packId, String versionId) {
        return repository.findVersion(packId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("version 不存在: " + packId + "@" + versionId));
    }

    public Map<String, Object> downloadIndex(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        return Map.of(
                "packId", version.packId(),
                "versionId", version.versionId(),
                "indexHash", version.indexHash(),
                "index", version.indexJson()
        );
    }

    public Map<String, Object> downloadOverridesLocation(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        if (version.overridesObjectKey() == null || version.overridesObjectKey().isBlank()) {
            throw new IllegalArgumentException("该版本无 overrides 文件");
        }
        return Map.of(
                "objectKey", version.overridesObjectKey(),
                "downloadUrl", "/api/plugins/launcher-adapter/v1/packs/" + packId + "/versions/" + versionId + "/overrides"
        );
    }

    public LauncherPackVersion uploadOverrides(String packId, String versionId, String base64Content,
                                               String expectedSha256) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        byte[] bytes = decodeOverridesZip(base64Content, expectedSha256);

        String objectKey = overridesKey(packId, versionId);
        fileStore.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "application/zip");

        List<LauncherEntry> entries = mergeEntries(
                parseUrlEntries(version.indexJson()), materializeManagedEntries(bytes));
        LauncherPack bumped = requirePack(packId).bumpRevision();
        LauncherPackVersion next = version.withOverrides(objectKey, entries, bumped.revision());
        repository.saveVersion(next);
        repository.savePack(bumped);
        return next;
    }

    public byte[] readOverridesBytes(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        if (version.overridesObjectKey() == null || version.overridesObjectKey().isBlank()) {
            throw new IllegalArgumentException("该版本无 overrides 文件");
        }
        return readStoredBytes(version.overridesObjectKey());
    }

    // ---------- 协议 v2 §5：对账端点视图 ----------

    /** head：廉价更新检测，客户端本地记录 revision/etag，一致即跳过。 */
    public Map<String, Object> headView(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        String shortHash = version.indexHash().length() >= 8
                ? version.indexHash().substring(0, 8)
                : (version.isVanilla() ? "vanilla" : "draft");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", version.revision());
        body.put("etag", "\"" + version.revision() + "-" + shortHash + "\"");
        return body;
    }

    /** manifest：对账全量描述（§5.2）。 */
    public Map<String, Object> manifestView(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packId", version.packId());
        body.put("versionId", version.versionId());
        body.put("revision", version.revision());
        body.put("channel", version.channel());
        body.put("gameVersion", version.gameVersion());
        body.put("loader", version.loader());
        body.put("loaderVersion", version.loaderVersion());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LauncherEntry entry : version.entries()) {
            entries.add(entryView(entry));
        }
        body.put("entries", entries);
        body.put("ignorePatterns", version.ignorePatterns());
        if (!version.isVanilla()) {
            body.put("mrpack", "/api/plugins/launcher-adapter/v2/packs/" + packId
                    + "/versions/" + versionId + "/mrpack");
        }
        return body;
    }

    /**
     * 读取 managed 文件（内容寻址）。安全约束：sha1 必须出现在该 pack 任一
     * 版本的 entries 里，否则拒绝——防止借内容寻址读取插件文件存储里的任意对象。
     */
    public byte[] readManagedFile(String packId, String sha1) {
        if (sha1 == null || !sha1.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("sha1 必须是 40 位小写十六进制");
        }
        requirePack(packId);
        boolean owned = repository.listVersions(packId).stream()
                .flatMap(v -> v.entries().stream())
                .anyMatch(e -> sha1.equals(e.sha1()) && e.source().isManaged());
        if (!owned) {
            throw new IllegalArgumentException("文件不存在或不属于该 pack: " + sha1);
        }
        return readStoredBytes(managedKey(sha1));
    }

    /** 重组标准 mrpack 首装快照（§5.2）：modrinth.index.json + overrides/*。 */
    public byte[] buildMrpackSnapshot(String packId, String versionId) {
        LauncherPackVersion version = requireVersion(packId, versionId);
        if (version.isVanilla()) {
            throw new IllegalArgumentException("VANILLA 版本无 mrpack 快照，请按 manifest 逐条对账安装");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(version.indexJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (!version.overridesObjectKey().isBlank()) {
                byte[] overrides = readStoredBytes(version.overridesObjectKey());
                try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(overrides), StandardCharsets.UTF_8)) {
                    ZipEntry entry;
                    while ((entry = in.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry("overrides/" + entry.getName()));
                        zip.write(in.readAllBytes());
                        zip.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("重组 mrpack 快照失败: " + packId + "@" + versionId, e);
        }
        return buffer.toByteArray();
    }

    private Map<String, Object> entryView(LauncherEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("path", entry.path());
        view.put("sha1", entry.sha1());
        view.put("size", entry.size());
        view.put("tier", entry.tier());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", entry.source().type());
        if (!entry.source().url().isBlank()) {
            source.put("url", entry.source().url());
        }
        view.put("source", source);
        view.put("env", Map.of("client", entry.env().client(), "server", entry.env().server()));
        return view;
    }

    // ---------- entries 物化 ----------

    /** 解析 mrpack index 的 files[] 为 url 条目（sha1 必填，缺 sha1 直接拒绝发布）。 */
    private List<LauncherEntry> parseUrlEntries(String indexJson) {
        if (indexJson == null || indexJson.isBlank()) {
            return List.of();
        }
        JsonNode root = readJson(indexJson);
        JsonNode files = root.get("files");
        if (files == null || !files.isArray()) {
            return List.of();
        }
        List<LauncherEntry> entries = new ArrayList<>();
        for (JsonNode file : files) {
            String path = text(file, "path");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("index files[] 缺少 path");
            }
            JsonNode hashes = file.get("hashes");
            String sha1 = hashes == null ? null : text(hashes, "sha1");
            if (sha1 == null || !sha1.matches("^[0-9a-f]{40}$")) {
                throw new IllegalArgumentException("index files[] 缺少 hashes.sha1（40 位小写 hex）: " + path);
            }
            long size = file.has("fileSize") ? file.get("fileSize").asLong(0) : 0;
            JsonNode downloadsNode = file.get("downloads");
            String url = null;
            if (downloadsNode != null && downloadsNode.isArray() && !downloadsNode.isEmpty()) {
                url = downloadsNode.get(0).asText(null);
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("index files[] 缺少 downloads: " + path);
            }
            JsonNode envNode = file.get("env");
            LauncherEntry.Env env = envNode == null
                    ? LauncherEntry.Env.defaults()
                    : new LauncherEntry.Env(text(envNode, "client"), text(envNode, "server"));
            entries.add(LauncherEntry.remote(path, sha1, size, url, env));
        }
        return entries;
    }

    /** 从 index.dependencies 推导 gameVersion/loader/loaderVersion。 */
    private GameInfo parseGameInfo(String indexJson) {
        JsonNode dependencies = readJson(indexJson).get("dependencies");
        if (dependencies == null || !dependencies.isObject()) {
            return new GameInfo("", "vanilla", "");
        }
        String gameVersionText = text(dependencies, "minecraft");
        String gameVersion = gameVersionText == null ? "" : gameVersionText;
        Map<String, String> loaderKeys = Map.of(
                "fabric-loader", "fabric",
                "forge", "forge",
                "neoforge", "neoforge",
                "quilt-loader", "quilt"
        );
        for (Map.Entry<String, String> candidate : loaderKeys.entrySet()) {
            String loaderVersion = text(dependencies, candidate.getKey());
            if (loaderVersion != null && !loaderVersion.isBlank()) {
                return new GameInfo(gameVersion, candidate.getValue(), loaderVersion);
            }
        }
        return new GameInfo(gameVersion, "vanilla", "");
    }

    private record GameInfo(String gameVersion, String loader, String loaderVersion) {
    }

    /** 解包 overrides zip：每个文件按 sha1 内容寻址入文件存储并生成 managed 条目。 */
    private List<LauncherEntry> materializeManagedEntries(byte[] zipBytes) {
        List<LauncherEntry> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = in.readAllBytes();
                String sha1 = sha1Hex(content);
                storeManagedFile(sha1, content);
                entries.add(LauncherEntry.managed(entry.getName(), sha1, content.length));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("overrides zip 解包失败: " + e.getMessage(), e);
        }
        return entries;
    }

    /** url 条目在前、managed 条目在后；同路径 managed 覆盖（overrides 优先于 CDN 文件）。 */
    private List<LauncherEntry> mergeEntries(List<LauncherEntry> urlEntries, List<LauncherEntry> managedEntries) {
        Map<String, LauncherEntry> byPath = new LinkedHashMap<>();
        for (LauncherEntry entry : urlEntries) {
            byPath.put(entry.path(), entry);
        }
        for (LauncherEntry entry : managedEntries) {
            byPath.put(entry.path(), entry);
        }
        return List.copyOf(byPath.values());
    }

    private void storeManagedFile(String sha1, byte[] content) {
        String key = managedKey(sha1);
        try {
            if (fileStore.get(key) != null) {
                return;
            }
        } catch (RuntimeException ignored) {
            // 读取失败按不存在处理，重新写入
        }
        fileStore.put(key, new ByteArrayInputStream(content), content.length, "application/octet-stream");
    }

    private String managedKey(String sha1) {
        return "files/" + sha1;
    }

    // ---------- 基础校验与工具 ----------

    public String overridesKey(String packId, String versionId) {
        return "overrides/" + packId + "/" + versionId + ".zip";
    }

    private byte[] decodeOverridesZip(String base64Content, String expectedSha256) {
        if (base64Content == null || base64Content.isBlank()) {
            throw new IllegalArgumentException("overridesContentBase64 不能为空");
        }
        if (base64Content.length() > MAX_OVERRIDES_BYTES) {
            throw new IllegalArgumentException("overrides 体积超过上限 " + MAX_OVERRIDES_BYTES + " bytes");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("overridesContentBase64 解码失败: " + e.getMessage(), e);
        }
        if (bytes.length < 4) {
            throw new IllegalArgumentException("overrides 字节过短，不是合法 zip");
        }
        if (bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IllegalArgumentException("overrides 必须以 zip magic number 开头");
        }
        String actualSha256 = sha256Hex(bytes);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new IllegalArgumentException(
                    "overrides SHA-256 与 expectedSha256 不一致: expected=" + expectedSha256
                            + " actual=" + actualSha256);
        }
        return bytes;
    }

    private byte[] readStoredBytes(String objectKey) {
        PluginStoredFile file = fileStore.get(objectKey);
        if (file == null) {
            throw new IllegalStateException("文件丢失: " + objectKey);
        }
        try (InputStream in = file.inputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + objectKey, e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    void validateDownloads(List<String> downloads) {
        if (downloads == null || downloads.isEmpty()) {
            return;
        }
        for (String raw : downloads) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("downloads 不能为空字符串");
            }
            String host = extractHost(raw.trim());
            if (!isAllowedDownloadHost(host)) {
                throw new IllegalArgumentException("downloads 主机不在白名单: " + host);
            }
        }
    }

    static boolean isAllowedDownloadHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (DEFAULT_DOWNLOAD_HOSTS.contains(normalized)) {
            return true;
        }
        for (String allowed : DEFAULT_DOWNLOAD_HOSTS) {
            if (normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void validateIndex(String indexJson) {
        if (indexJson == null || indexJson.isBlank()) {
            throw new IllegalArgumentException("indexJson 不能为空");
        }
        if (!indexJson.contains("\"files\"") || !indexJson.contains("\"formatVersion\"")) {
            throw new IllegalArgumentException("indexJson 必须包含 formatVersion 与 files 字段（mrpack 协议）");
        }
    }

    private void validateIndexHash(String hash) {
        if (hash == null || !hash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("indexHash 必须是 64 位小写十六进制 SHA-256");
        }
    }

    private String extractHost(String value) {
        String candidate = value;
        if (candidate.contains("://")) {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("downloads URL 必须使用 https: " + value);
            }
            candidate = uri.getHost();
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalArgumentException("downloads URL 缺少主机: " + value);
            }
        }
        if (candidate.contains("/") || candidate.contains("\\") || candidate.contains(":")) {
            throw new IllegalArgumentException("downloads 主机非法: " + value);
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sha1Hex(byte[] data) {
        return hexDigest("SHA-1", data);
    }

    private String sha256Hex(byte[] data) {
        return hexDigest("SHA-256", data);
    }

    private String hexDigest(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " 不可用", e);
        }
    }
}
