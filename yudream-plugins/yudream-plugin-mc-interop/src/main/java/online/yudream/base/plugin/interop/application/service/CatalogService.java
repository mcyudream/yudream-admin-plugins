package online.yudream.base.plugin.interop.application.service;

import online.yudream.base.plugin.interop.domain.McInteropBusinessException;
import online.yudream.base.plugin.interop.domain.aggregate.CatalogEntry;
import online.yudream.base.plugin.interop.infrastructure.repository.InteropRepository;
import online.yudream.base.plugin.interop.infrastructure.support.JsonSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目录管理与统一出口（mc-interop v1）。
 *
 * <p>校验 fail-closed（协议 §6.1）：loader/artifact format 枚举外值、非法哈希、
 * http 地址一律拒绝入库，出口数据因此保证合法。出口只含 published 条目（§3.1），
 * ETag 由出口内容摘要派生（§2.2）。</p>
 */
public class CatalogService {

    private static final Set<String> LOADERS =
            Set.of("vanilla", "fabric", "forge", "neoforge", "quilt");
    private static final Set<String> ARTIFACT_FORMATS =
            Set.of("mrpack", "multimc", "curseforge-zip", "server-zip", "raw");
    private static final Set<String> DISTRIBUTION_SYSTEMS = Set.of("yda-launcher-v2", "yap-mip");
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final InteropRepository repository;
    /** union 身份源（软依赖桥；测试/未装 union 时为 null → 回退本插件设置）。 */
    private final java.util.function.Supplier<online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord> unionIdentity;
    /** ymcl-adapter 聚合目录（软依赖桥；未装适配器时为 null → 手填兜底）。 */
    private final java.util.function.Supplier<List<online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry>> adapterCatalog;

    /** peers 互采合并缓存（5 分钟），避免出口每次请求都外拉友站清单。 */
    private volatile List<Map<String, Object>> mergedPeerPacksCache;
    private volatile long mergedPeerPacksAt;
    private final java.net.http.HttpClient interopHttp = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    public CatalogService(InteropRepository repository) {
        this(repository, null, null);
    }

    public CatalogService(InteropRepository repository,
                          java.util.function.Supplier<online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord> unionIdentity) {
        this(repository, unionIdentity, null);
    }

    public CatalogService(InteropRepository repository,
                          java.util.function.Supplier<online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord> unionIdentity,
                          java.util.function.Supplier<List<online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry>> adapterCatalog) {
        this.repository = repository;
        this.unionIdentity = unionIdentity;
        this.adapterCatalog = adapterCatalog;
    }

    // ---- settings ----

    public Map<String, Object> settings() {
        Map<String, Object> settings = repository.settings();
        online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord identity = resolveIdentity();
        Map<String, Object> view = new LinkedHashMap<>();
        // siteId/siteName/domain = 生效值（union 优先）；identitySource 告知前端来源与可编辑性
        view.put("siteId", identity != null && identity.code() != null ? identity.code() : text(settings.get("siteId")));
        view.put("siteName", identity != null && identity.name() != null && !identity.name().isBlank()
                ? identity.name() : text(settings.get("siteName")));
        view.put("domain", identity != null && identity.origin() != null && !identity.origin().isBlank()
                ? stripScheme(identity.origin()) : text(settings.get("domain")));
        view.put("description", text(settings.get("description")));
        view.put("identitySource", identity != null ? "union" : "local");
        return view;
    }

    /** 生效身份：union（站点身份单一事实源）优先，未安装/未配置回退本插件设置。 */
    private online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord resolveIdentity() {
        if (unionIdentity != null) {
            online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord identity = unionIdentity.get();
            if (identity != null && identity.code() != null && !identity.code().isBlank()) {
                return identity;
            }
        }
        return null;
    }

    public Map<String, Object> saveSettings(String siteId, String siteName, String description, String domain) {
        requirePattern(siteId, "^[A-Za-z0-9._-]{1,128}$", "siteId 必填且只能包含字母数字 ._ -（≤128 位）");
        if (siteName == null || siteName.isBlank() || siteName.length() > 128) {
            throw new McInteropBusinessException("siteName 必填且 ≤128 字");
        }
        if (description != null && description.length() > 2000) {
            throw new McInteropBusinessException("description ≤2000 字");
        }
        requirePattern(domain, "^[A-Za-z0-9.-]{1,253}$", "domain 必填（根域名，不带 scheme/path）");
        Map<String, Object> document = new LinkedHashMap<>(repository.settings());
        document.put("siteId", siteId.trim());
        document.put("siteName", siteName.trim());
        document.put("description", description == null ? "" : description.trim());
        document.put("domain", domain.trim());
        document.put("updatedAt", System.currentTimeMillis());
        repository.saveSettings(document);
        return settings();
    }

    // ---- entries ----

    public CatalogEntry saveEntry(CatalogEntry input) {
        requirePattern(input.id(), "^[A-Za-z0-9._-]{1,128}$", "id 必填且只能包含字母数字 ._ -（≤128 位）");
        if (input.name() == null || input.name().isBlank() || input.name().length() > 128) {
            throw new McInteropBusinessException("name 必填且 ≤128 字");
        }
        if (input.summary() != null && input.summary().length() > 2000) {
            throw new McInteropBusinessException("summary ≤2000 字");
        }
        if (input.gameVersion() == null || input.gameVersion().isBlank()
                || input.gameVersion().length() > 64) {
            throw new McInteropBusinessException("gameVersion 必填（原样透传，≤64 字）");
        }
        if (input.loader() == null || !LOADERS.contains(input.loader())) {
            throw new McInteropBusinessException("loader 必须是 vanilla/fabric/forge/neoforge/quilt");
        }
        boolean vanilla = "vanilla".equals(input.loader());
        if (!vanilla && (input.loaderVersion() == null || input.loaderVersion().isBlank()
                || input.loaderVersion().length() > 64)) {
            throw new McInteropBusinessException("非 vanilla 必须填 loaderVersion");
        }
        if (input.serverAddress() == null || input.serverAddress().isBlank()
                || input.serverAddress().length() > 255) {
            throw new McInteropBusinessException("server.address 必填");
        }
        if (input.serverPort() != null && (input.serverPort() < 1 || input.serverPort() > 65535)) {
            throw new McInteropBusinessException("server.port 必须在 1-65535");
        }

        boolean hasArtifact = input.artifactUrl() != null && !input.artifactUrl().isBlank();
        Long revision = input.revision();
        String format = input.artifactFormat();
        String sha1 = blankToNull(input.sha1());
        String sha512 = blankToNull(input.sha512());
        if (hasArtifact) {
            if (!input.artifactUrl().startsWith("https://")) {
                throw new McInteropBusinessException("artifact.url 必须是绝对 https 地址");
            }
            if (input.artifactUrl().length() > 2048) {
                throw new McInteropBusinessException("artifact.url 过长");
            }
            if (input.artifactSize() == null || input.artifactSize() <= 0) {
                throw new McInteropBusinessException("artifact.size 必填（字节数）");
            }
            if (format == null || !ARTIFACT_FORMATS.contains(format)) {
                throw new McInteropBusinessException(
                        "artifact.format 必须是 mrpack/multimc/curseforge-zip/server-zip/raw");
            }
            if (revision == null || revision < 0) {
                throw new McInteropBusinessException("有整合包的条目必须提供非负 revision");
            }
            // 自动递增：artifact 内容指针变化时 revision 单调 +1（协议 §4）。
            CatalogEntry existing = repository.findEntry(input.id()).orElse(null);
            if (existing != null && existing.hasArtifact() && existing.revision() != null
                    && revision <= existing.revision()
                    && contentChanged(existing, input)) {
                revision = existing.revision() + 1;
            }
            if (sha1 != null && !sha1.matches("[0-9a-fA-F]{40}")) {
                throw new McInteropBusinessException("sha1 必须是 40 位 hex");
            }
            if (sha512 != null && !sha512.matches("[0-9a-fA-F]{128}")) {
                throw new McInteropBusinessException("sha512 必须是 128 位 hex");
            }
            if (sha1 == null && sha512 == null) {
                throw new McInteropBusinessException("sha1/sha512 至少提供一个");
            }
        } else {
            // 纯服务器条目：无 artifact 即无 revision（协议字段规则）。
            revision = null;
            format = null;
            sha1 = null;
            sha512 = null;
        }
        String distSystem = blankToNull(input.distSystem());
        if (distSystem != null && !DISTRIBUTION_SYSTEMS.contains(distSystem)) {
            throw new McInteropBusinessException("distribution.system 只支持 yda-launcher-v2 / yap-mip");
        }

        CatalogEntry entry = new CatalogEntry(input.id().trim(), input.name().trim(),
                blankToNull(input.summary()), revision, input.gameVersion().trim(), input.loader(),
                vanilla ? null : input.loaderVersion(), hasArtifact ? format : null,
                hasArtifact ? input.artifactUrl() : null, hasArtifact ? input.artifactSize() : null,
                hasArtifact ? sha1 : null, hasArtifact ? sha512 : null,
                hasArtifact ? distSystem : null, hasArtifact ? blankToNull(input.distPackId()) : null,
                hasArtifact ? blankToNull(input.distVersionId()) : null,
                input.serverAddress().trim(), input.serverPort(), input.published(),
                System.currentTimeMillis());
        repository.saveEntry(entry);
        return entry;
    }

    public List<CatalogEntry> pageEntries(String keyword, boolean onlyPublished, int page, int size) {
        String needle = keyword == null || keyword.isBlank() ? null
                : keyword.trim().toLowerCase();
        List<CatalogEntry> matched = new ArrayList<>();
        for (CatalogEntry entry : repository.allEntries()) {
            if (onlyPublished && !entry.published()) {
                continue;
            }
            if (needle != null && !matches(entry, needle)) {
                continue;
            }
            matched.add(entry);
        }
        matched.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new ArrayList<>(matched.subList(from, to));
    }

    public long countEntries(String keyword, boolean onlyPublished) {
        return pageEntries(keyword, onlyPublished, 1, Integer.MAX_VALUE).size();
    }

    /**
     * 从 ymcl-adapter 目录导入：每条 pack×绑定组合生成/更新一个已发布条目，
     * hash/size/revision 全部自动转换（distribution=yap-mip）。loader 白名单
     * 外的条目跳过并计入 skipped。
     */
    public Map<String, Object> importFromAdapter() {
        if (adapterCatalog == null) {
            throw new McInteropBusinessException("ymcl-adapter 未安装，无法导入");
        }
        List<online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry> rows =
                adapterCatalog.get();
        String origin = effectiveOrigin();
        int imported = 0;
        List<String> skipped = new ArrayList<>();
        for (online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry row : rows) {
            String loader = row.loaderType() == null ? "vanilla" : row.loaderType().toLowerCase();
            if (!List.of("vanilla", "fabric", "forge", "neoforge", "quilt").contains(loader)) {
                skipped.add(row.packId() + "（loader " + row.loaderType() + " 不受支持）");
                continue;
            }
            if (row.sha512() == null || row.sha512().isBlank()) {
                skipped.add(row.packId() + "（缺少 sha512）");
                continue;
            }
            String entryId = (row.packId() + "." + row.serverId()).replaceAll("[^A-Za-z0-9._-]", "_");
            CatalogEntry existing = repository.findEntry(entryId).orElse(null);
            Long revision = parseRevision(row.version());
            if (revision == null) {
                revision = existing != null && existing.revision() != null ? existing.revision() + 1 : 1L;
            }
            String name = (row.packName() == null || row.packName().isBlank() ? row.packId() : row.packName())
                    + (row.serverName() != null && !row.serverName().isBlank() ? " · " + row.serverName() : "");
            try {
                // mrpackPath 为插件内相对路径（/mip/...），完整地址需挂 ymcl-adapter 的 /api 前缀
            String adapterBase = origin.isBlank() ? "" : origin + "/api/plugins/ymcl-adapter";
            saveEntry(new CatalogEntry(entryId, name,
                    row.serverAddress() == null ? null : "绑定服务器：" + row.serverAddress(),
                    revision, row.gameVersion() == null ? "unknown" : row.gameVersion(),
                    loader, row.loaderVersion(), "mrpack",
                    adapterBase + row.mrpackPath(), row.sizeBytes(), null, row.sha512(),
                    "yap-mip", row.packId(), row.version(),
                    row.serverAddress(), null, true, System.currentTimeMillis()));
                imported++;
            } catch (McInteropBusinessException e) {
                skipped.add(row.packId() + "（" + e.getMessage() + "）");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imported", imported);
        body.put("skipped", skipped);
        return body;
    }

    private String effectiveOrigin() {
        if (unionIdentity != null) {
            online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord identity = unionIdentity.get();
            if (identity != null && identity.origin() != null && !identity.origin().isBlank()) {
                return identity.origin().trim();
            }
        }
        Map<String, Object> settings = repository.settings();
        String domain = text(settings.get("domain"));
        return domain == null || domain.isBlank() ? "" : "https://" + domain;
    }

    private static Long parseRevision(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(version.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public CatalogEntry setPublished(String id, boolean published) {
        CatalogEntry entry = repository.findEntry(id)
                .orElseThrow(() -> new McInteropBusinessException("条目不存在"));
        CatalogEntry updated = new CatalogEntry(entry.id(), entry.name(), entry.summary(),
                entry.revision(), entry.gameVersion(), entry.loader(), entry.loaderVersion(),
                entry.artifactFormat(), entry.artifactUrl(), entry.artifactSize(), entry.sha1(),
                entry.sha512(), entry.distSystem(), entry.distPackId(), entry.distVersionId(),
                entry.serverAddress(), entry.serverPort(), published, System.currentTimeMillis());
        repository.saveEntry(updated);
        return updated;
    }

    public void deleteEntry(String id) {
        repository.findEntry(id).orElseThrow(() -> new McInteropBusinessException("条目不存在"));
        repository.deleteEntry(id);
    }

    // ---- peers ----

    public List<Map<String, Object>> peers() {
        return repository.allPeers();
    }

    public void savePeer(String siteId, String name, String description, String domain, String manifestUrl) {
        requirePattern(siteId, "^[A-Za-z0-9._-]{1,128}$", "siteId 必填且只能包含字母数字 ._ -");
        if (manifestUrl == null || !manifestUrl.startsWith("https://")) {
            throw new McInteropBusinessException("manifestUrl 必须是 https 地址");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", blankToNull(name));
        document.put("description", blankToNull(description));
        document.put("domain", blankToNull(domain));
        document.put("manifestUrl", manifestUrl.trim());
        repository.savePeer(siteId.trim(), document);
    }

    public void deletePeer(String siteId) {
        repository.deletePeer(siteId);
    }

    // ---- 统一出口 ----

    /**
     * SiteDocument（协议 §3）：仅 published 条目；sha1/sha512 两键恒输出、未知写 null；
     * peers 恒输出；updatedAt 只在顶层（ISO-8601 UTC）。
     */
    public Map<String, Object> siteDocument() {
        Map<String, Object> settings = repository.settings();
        online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord identity = resolveIdentity();
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("id", identity != null ? identity.code() : text(settings.get("siteId")));
        site.put("name", identity != null && identity.name() != null && !identity.name().isBlank()
                ? identity.name() : text(settings.get("siteName")));
        site.put("description", text(settings.get("description")) == null ? "" : text(settings.get("description")));
        site.put("domain", identity != null && identity.origin() != null && !identity.origin().isBlank()
                ? stripScheme(identity.origin()) : text(settings.get("domain")));

        List<Map<String, Object>> packs = new ArrayList<>();
        long latest = settingsUpdated(settings);
        for (CatalogEntry entry : repository.allEntries()) {
            if (!entry.published()) {
                continue;
            }
            latest = Math.max(latest, entry.updatedAt());
            packs.add(entryView(entry));
        }
        mergePeerPacks(packs, latest);

        List<Map<String, Object>> peers = new ArrayList<>();
        for (Map<String, Object> peer : repository.allPeers()) {
            latest = Math.max(latest, nullableLong(peer.get("addedAt"), 0L));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("siteId", text(peer.get("id")));
            view.put("name", text(peer.get("name")));
            view.put("description", text(peer.get("description")));
            view.put("domain", text(peer.get("domain")));
            view.put("manifestUrl", text(peer.get("manifestUrl")));
            peers.add(view);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("site", site);
        document.put("packs", packs);
        document.put("peers", peers);
        document.put("updatedAt", ISO_UTC.format(Instant.ofEpochMilli(latest)));
        return document;
    }

    /**
     * peers 互采合并（协议 §7.2）：拉取各友站清单，条目带 source 溯源；
     * 与本站/已合并条目 id 冲突即丢弃；单站失败只跳过。缓存 5 分钟。
     */
    @SuppressWarnings("unchecked")
    private void mergePeerPacks(List<Map<String, Object>> packs, long latestOwn) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cached = mergedPeerPacksCache;
        if (cached != null && now - mergedPeerPacksAt < 5 * 60_000L) {
            packs.addAll(cached);
            return;
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> pack : packs) {
            seen.add(text(pack.get("id")));
        }
        for (Map<String, Object> peer : repository.allPeers()) {
            String siteId = text(peer.get("id"));
            String manifestUrl = text(peer.get("manifestUrl"));
            String domain = text(peer.get("domain"));
            if (siteId == null || manifestUrl == null || !manifestUrl.startsWith("https://")) {
                continue;
            }
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(manifestUrl))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                java.net.http.HttpResponse<byte[]> response =
                        interopHttp.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body().length > 1024 * 1024) {
                    continue;
                }
                Map<String, Object> remote = online.yudream.base.plugin.interop.infrastructure.support.JsonSupport.getMapper().readValue(
                        new String(response.body(), StandardCharsets.UTF_8), Map.class);
                if (!(remote.get("packs") instanceof List<?> remotePacks)) {
                    continue;
                }
                for (Object packValue : remotePacks) {
                    if (!(packValue instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> pack = online.yudream.base.plugin.interop.infrastructure.support.JsonSupport.getMapper().convertValue(packValue, Map.class);
                    String id = text(pack.get("id"));
                    if (id == null || !seen.add(id)) {
                        continue;
                    }
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("siteId", siteId);
                    source.put("domain", domain == null ? "" : domain);
                    pack.put("source", source);
                    merged.add(pack);
                }
            } catch (Exception e) {
                // 单站失败只跳过（§7.2）
            }
        }
        mergedPeerPacksCache = merged;
        mergedPeerPacksAt = now;
        packs.addAll(merged);
    }

    /** 出口内容摘要派生的强 ETag（协议 §2.2 建议由 updatedAt/revision 派生）。 */
    public String etag() {
        try {
            Map<String, Object> document = siteDocument();
            byte[] bytes = Json.write(document);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + HexFormat.of().formatHex(digest).substring(0, 32) + "\"";
        } catch (Exception e) {
            throw new McInteropBusinessException("出口序列化失败：" + e.getMessage());
        }
    }

    private Map<String, Object> entryView(CatalogEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entry.id());
        view.put("name", entry.name());
        view.put("summary", entry.summary());
        if (entry.hasArtifact()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("format", entry.artifactFormat());
            artifact.put("url", entry.artifactUrl());
            // 协议 §4：size/revision 必须是整数（文档库可能回读出数字字符串，归一化）
            artifact.put("size", asLong(entry.artifactSize()));
            artifact.put("sha1", entry.sha1());
            artifact.put("sha512", entry.sha512());
            if (entry.distSystem() != null) {
                Map<String, Object> distribution = new LinkedHashMap<>();
                distribution.put("system", entry.distSystem());
                distribution.put("packId", entry.distPackId());
                distribution.put("versionId", entry.distVersionId());
                artifact.put("distribution", distribution);
            }
            view.put("artifact", artifact);
            view.put("revision", asLong(entry.revision()));
        }
        view.put("gameVersion", entry.gameVersion());
        view.put("loader", entry.loader());
        if (entry.loaderVersion() != null) {
            view.put("loaderVersion", entry.loaderVersion());
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", entry.serverAddress());
        if (entry.serverPort() != null) {
            server.put("port", asInt(entry.serverPort()));
        }
        view.put("server", server);
        return view;
    }

    /** 文档库回读可能带出数字字符串；出口统一归一为 Long（协议整数类型）。 */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }


    // ---- helpers ----

    private static boolean contentChanged(CatalogEntry existing, CatalogEntry input) {
        return !java.util.Objects.equals(existing.artifactUrl(), input.artifactUrl())
                || !java.util.Objects.equals(existing.artifactSize(), input.artifactSize())
                || !java.util.Objects.equals(blankToNull(existing.sha1()), blankToNull(input.sha1()))
                || !java.util.Objects.equals(blankToNull(existing.sha512()), blankToNull(input.sha512()));
    }

    private static boolean matches(CatalogEntry entry, String needle) {
        String text = String.join(" ",
                nullSafe(entry.id()), nullSafe(entry.name()), nullSafe(entry.summary()),
                nullSafe(entry.gameVersion()), nullSafe(entry.loader()),
                nullSafe(entry.serverAddress())).toLowerCase();
        return text.contains(needle);
    }

    private static long settingsUpdated(Map<String, Object> settings) {
        return nullableLong(settings.get("updatedAt"), 0L);
    }

    private static Long nullableLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 协议 domain = 根域名（host，不带 scheme/path）；union origin 为完整 URL 时剥离。 */
    private static String stripScheme(String origin) {
        String trimmed = origin.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("https://")) {
            trimmed = trimmed.substring("https://".length());
        } else if (lower.startsWith("http://")) {
            trimmed = trimmed.substring("http://".length());
        }
        int slash = trimmed.indexOf('/');
        return slash >= 0 ? trimmed.substring(0, slash) : trimmed;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requirePattern(String value, String pattern, String message) {
        if (value == null || !value.trim().matches(pattern)) {
            throw new McInteropBusinessException(message);
        }
    }

    /** 轻量 JSON 序列化（仅本插件出口使用；值均为 String/Number/Boolean/Map/List/null）。 */
    public static final class Json {

        private Json() {
        }

        public static byte[] write(Map<String, Object> document) {
            StringBuilder builder = new StringBuilder();
            writeMap(builder, document);
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void writeMap(StringBuilder builder, Map<String, Object> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, entry.getKey());
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        }

        private static void writeList(StringBuilder builder, List<?> list) {
            builder.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
        }

        @SuppressWarnings("unchecked")
        private static void writeValue(StringBuilder builder, Object value) {
            switch (value) {
                case null -> builder.append("null");
                case String text -> writeString(builder, text);
                case Number number -> builder.append(number);
                case Boolean flag -> builder.append(flag);
                case Map<?, ?> map -> writeMap(builder, (Map<String, Object>) map);
                case List<?> list -> writeList(builder, list);
                default -> writeString(builder, String.valueOf(value));
            }
        }

        private static void writeString(StringBuilder builder, String value) {
            builder.append('"');
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                switch (ch) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (ch < 0x20) {
                            builder.append(String.format("\\u%04x", (int) ch));
                        } else {
                            builder.append(ch);
                        }
                    }
                }
            }
            builder.append('"');
        }
    }
}
