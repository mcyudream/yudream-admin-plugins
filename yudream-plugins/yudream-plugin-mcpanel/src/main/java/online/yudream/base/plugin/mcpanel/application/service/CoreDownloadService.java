package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端核心下载：优先 FastMirror（可配置镜像站），失败自动官方源兜底。
 * 版本列表供 YdTablePicker 取数；解析结果写入实例 install 计划（节点 install.run 下载）。
 */
public class CoreDownloadService {

    private static final String USER_AGENT =
            "YuDream-McPanel/0.4 (https://yudream.online; admin-plugin-mcpanel)";
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Fabric/Quilt 官方 meta：loader / installer / server jar 为同构端点。 */
    private static final String FABRIC_META_BASE = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META_BASE = "https://meta.quiltmc.org/v3";
    /** Fabric installer 列表不可达时的兜底（游戏版本无关，现网长期使用）。loader 无兜底：宁可显式失败也不装过期版本。 */
    private static final String FALLBACK_FABRIC_INSTALLER = "1.0.3";

    public static final List<Map<String, String>> CORE_CATALOG = List.of(
            Map.of("kind", "paper", "label", "Paper", "project", "paper"),
            Map.of("kind", "purpur", "label", "Purpur", "project", "purpur"),
            Map.of("kind", "folia", "label", "Folia", "project", "folia"),
            Map.of("kind", "vanilla", "label", "原版", "project", "vanilla"),
            Map.of("kind", "fabric", "label", "Fabric", "project", "fabric"),
            Map.of("kind", "quilt", "label", "Quilt", "project", "quilt"),
            Map.of("kind", "velocity", "label", "Velocity", "project", "velocity"));

    /** FastMirror → 官方 的解析来源标记。 */
    public record CoreDownloadPlan(String kind, String mcVersion, String url, String source,
                                   String fileName, String note) {
    }

    private final Supplier<String> fastMirrorBaseSupplier;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedVersion> metaVersionCache = new ConcurrentHashMap<>();
    private final JsonFetcher fetcher;

    public interface Supplier<T> {
        T get();
    }

    /** JSON 抓取函数（meta / 镜像版本接口），测试可注入假实现。 */
    interface JsonFetcher {
        JsonNode fetch(String url) throws Exception;
    }

    private record CacheEntry(List<Map<String, String>> rows, long expiresAt) {
    }

    private record CachedVersion(String version, long expiresAt) {
    }

    public CoreDownloadService(Supplier<String> fastMirrorBaseSupplier) {
        this(fastMirrorBaseSupplier, null);
    }

    CoreDownloadService(Supplier<String> fastMirrorBaseSupplier, JsonFetcher fetcher) {
        this.fastMirrorBaseSupplier = fastMirrorBaseSupplier;
        this.fetcher = fetcher != null ? fetcher : CoreDownloadService::getJson;
    }

    public List<Map<String, Object>> listCores() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> item : CORE_CATALOG) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("id", item.get("kind"));
            rows.add(row);
        }
        return rows;
    }

    /** 版本列表（YdTablePicker fetcher）：稳定优先，支持 keyword 过滤 + 内存分页。 */
    public Map<String, Object> pageVersions(String kind, int page, int size, String keyword) {
        if (kind == null || kind.isBlank()) {
            throw McpanelBusinessException.invalid("缺少核心类型");
        }
        List<Map<String, String>> all = versionsOf(kind.trim().toLowerCase());
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Map<String, String>> filtered = all.stream()
                .filter(r -> kw.isEmpty()
                        || r.getOrDefault("version", "").toLowerCase().contains(kw)
                        || r.getOrDefault("label", "").toLowerCase().contains(kw))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("kind", kind);
        return result;
    }

    /** 解析下载计划：FastMirror 优先，官方兜底。 */
    public CoreDownloadPlan resolve(String kind, String mcVersion) {
        return resolve(kind, mcVersion, null);
    }

    /**
     * 解析下载计划：Fabric/Quilt 直接走官方 meta（loaderVersion 为整合包清单声明的版本，
     * 缺省取 meta 最新稳定版）；其余核心 FastMirror 优先、官方兜底。
     * 加载器核心不经镜像源：镜像接口无法表达 loader 版本约束，固定版本会复现
     * 「清单要新 loader、装出旧 loader」的兼容性故障。
     */
    public CoreDownloadPlan resolve(String kind, String mcVersion, String loaderVersion) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        String v = mcVersion == null ? "" : mcVersion.trim();
        if (k.isEmpty() || v.isEmpty()) {
            throw McpanelBusinessException.invalid("请选择核心类型与 MC 版本");
        }
        if ("fabric".equals(k) || "quilt".equals(k)) {
            return resolveMeta(k, v, loaderVersion);
        }
        String fmBase = normalizeBase(fastMirrorBaseSupplier.get());
        if (fmBase != null) {
            try {
                CoreDownloadPlan fromMirror = resolveFastMirror(fmBase, k, v);
                if (fromMirror != null) {
                    return fromMirror;
                }
            } catch (Exception ignored) {
                // 镜像不可用 → 官方兜底
            }
        }
        return resolveOfficial(k, v, loaderVersion);
    }

    // ---------- FastMirror ----------

    private CoreDownloadPlan resolveFastMirror(String base, String kind, String version) throws Exception {
        // 兼容多版路径：latest → all → build/download 直链
        for (String path : new String[]{
                "/api/v3/" + kind + "/" + version + "/latest",
                "/api/v3/" + kind + "/" + version + "/all"}) {
            JsonNode body = fetchJson(base + path);
            if (body == null) {
                continue;
            }
            String url = firstText(body, "data.download_url", "data.url", "download_url", "url");
            String build = firstText(body, "data.build", "build", "data.id");
            if (url == null || url.isBlank()) {
                if (build != null && !build.isBlank()) {
                    url = base + "/" + kind + "/" + version + "/" + build + "/download";
                }
            }
            if (url != null && !url.isBlank()) {
                return new CoreDownloadPlan(kind, version, url, "fastmirror",
                        jarName(kind, version), "FastMirror：" + base);
            }
            // all 列表：取最新一条
            JsonNode list = body.path("data").isArray() ? body.path("data") : body.path("data").path("builds");
            if (list.isArray() && !list.isEmpty()) {
                JsonNode last = list.get(list.size() - 1);
                String b = firstText(last, "build", "id", "name");
                if (b != null && !b.isBlank()) {
                    String direct = base + "/" + kind + "/" + version + "/" + b + "/download";
                    return new CoreDownloadPlan(kind, version, direct, "fastmirror",
                            jarName(kind, version), "FastMirror：" + base);
                }
            }
        }
        return null;
    }

    // ---------- 官方兜底 ----------

    private CoreDownloadPlan resolveOfficial(String kind, String version, String loaderVersion) {
        return switch (kind) {
            case "paper", "folia", "velocity" -> resolvePaperFamily(kind, version);
            case "purpur" -> resolvePurpur(version);
            case "vanilla" -> resolveVanilla(version);
            case "fabric", "quilt" -> resolveMeta(kind, version, loaderVersion);
            default -> throw McpanelBusinessException.invalid("暂不支持的核心类型：" + kind);
        };
    }

    private CoreDownloadPlan resolvePaperFamily(String project, String version) {
        try {
            String buildsUrl = "https://fill.papermc.io/v3/projects/" + project + "/versions/"
                    + version + "/builds";
            JsonNode builds = getJson(buildsUrl);
            if (builds == null) {
                throw McpanelBusinessException.invalid("官方源无法获取 " + project + " 构建列表");
            }
            String url = null;
            String buildId = null;
            if (builds.isArray()) {
                for (JsonNode build : builds) {
                    if ("STABLE".equalsIgnoreCase(build.path("channel").asText(""))) {
                        buildId = String.valueOf(build.path("id").asText(build.path("build").asText("")));
                        JsonNode download = build.path("downloads").path("server:default");
                        if (download.isMissingNode() || download.isNull()) {
                            download = build.path("downloads").elements().next();
                        }
                        url = download.path("url").asText(null);
                        break;
                    }
                }
            }
            if (url == null || url.isBlank()) {
                throw McpanelBusinessException.notFound(project + " " + version + " 暂无稳定构建");
            }
            return new CoreDownloadPlan(project, version, url, "official:" + project,
                    jarName(project, version + (buildId == null ? "" : "-" + buildId)),
                    "官方 Fill API");
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("官方源解析失败：" + error.getMessage());
        }
    }

    private CoreDownloadPlan resolvePurpur(String version) {
        String url = "https://api.purpurmc.org/v2/purpur/" + version + "/latest/download";
        return new CoreDownloadPlan("purpur", version, url, "official:purpur",
                jarName("purpur", version), "官方 Purpur API");
    }

    private CoreDownloadPlan resolveVanilla(String version) {
        try {
            JsonNode manifest = getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
            if (manifest == null) {
                throw McpanelBusinessException.invalid("无法获取原版版本清单");
            }
            String metaUrl = null;
            for (JsonNode item : manifest.path("versions")) {
                if (version.equals(item.path("id").asText())) {
                    metaUrl = item.path("url").asText(null);
                    break;
                }
            }
            if (metaUrl == null) {
                throw McpanelBusinessException.notFound("原版版本不存在：" + version);
            }
            JsonNode meta = getJson(metaUrl);
            String serverUrl = meta == null ? null : meta.path("downloads").path("server").path("url").asText(null);
            if (serverUrl == null || serverUrl.isBlank()) {
                // BMCLAPI 兜底
                serverUrl = "https://bmclapi2.bangbang93.com/version/" + version + "/server";
                return new CoreDownloadPlan("vanilla", version, serverUrl, "bmclapi",
                        jarName("server", version), "BMCLAPI 原版服务端");
            }
            return new CoreDownloadPlan("vanilla", version, serverUrl, "official:mojang",
                    jarName("server", version), "官方 Piston");
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            String fallback = "https://bmclapi2.bangbang93.com/version/" + version + "/server";
            return new CoreDownloadPlan("vanilla", version, fallback, "bmclapi",
                    jarName("server", version), "BMCLAPI 原版服务端（官方失败兜底）");
        }
    }

    // ---------- Fabric / Quilt meta ----------

    /**
     * meta server/jar 启动器：loader 版本按整合包清单声明，缺省取最新稳定版；
     * installer 取最新稳定版（Fabric 列表不可达时兜底 {@link #FALLBACK_FABRIC_INSTALLER}）。
     */
    private CoreDownloadPlan resolveMeta(String kind, String gameVersion, String loaderVersion) {
        boolean quilt = "quilt".equals(kind);
        String metaBase = quilt ? QUILT_META_BASE : FABRIC_META_BASE;
        String requested = loaderVersion == null ? "" : loaderVersion.trim();
        if (requested.contains("/") || requested.contains("..")
                || requested.chars().anyMatch(Character::isWhitespace)) {
            throw McpanelBusinessException.invalid("整合包 loader 版本不合法：" + requested);
        }
        String loader = requested.isEmpty()
                ? latestMetaVersion(metaBase + "/versions/loader", "")
                : requested;
        String installer = latestMetaVersion(metaBase + "/versions/installer",
                quilt ? "" : FALLBACK_FABRIC_INSTALLER);
        String url = metaBase + "/versions/loader/" + gameVersion + "/" + loader + "/" + installer + "/server/jar";
        return new CoreDownloadPlan(kind, gameVersion, url, "official:" + kind,
                jarName(kind + "-server", gameVersion),
                "官方 " + (quilt ? "Quilt" : "Fabric") + " meta（loader " + loader + "）");
    }

    /** meta 版本列表取最新稳定版（无 stable 标记时取首条），结果缓存 {@link #CACHE_TTL_MS}。 */
    private String latestMetaVersion(String listUrl, String fallback) {
        CachedVersion hit = metaVersionCache.get(listUrl);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return hit.version();
        }
        JsonNode body = fetchJsonQuietly(listUrl);
        String latest = null;
        if (body != null && body.isArray()) {
            String first = null;
            for (JsonNode item : body) {
                String v = item.path("version").asText("");
                if (v.isBlank()) {
                    continue;
                }
                if (first == null) {
                    first = v;
                }
                if (item.path("stable").asBoolean(false)) {
                    latest = v;
                    break;
                }
            }
            if (latest == null) {
                latest = first;
            }
        }
        if (latest == null) {
            if (fallback.isBlank()) {
                // 无法确定版本时不悄悄降级到过期版本
                throw McpanelBusinessException.invalid(
                        "无法从 " + listUrl + " 获取版本列表（meta 不可达），请稍后重试或改用上传核心");
            }
            return fallback; // 兜底不缓存，下次仍尝试 meta
        }
        metaVersionCache.put(listUrl, new CachedVersion(latest, now + CACHE_TTL_MS));
        return latest;
    }

    private List<Map<String, String>> versionsOf(String kind) {
        CacheEntry hit = cache.get(kind);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return hit.rows();
        }
        List<Map<String, String>> rows = loadVersions(kind);
        cache.put(kind, new CacheEntry(rows, now + CACHE_TTL_MS));
        return rows;
    }

    private List<Map<String, String>> loadVersions(String kind) {
        try {
            return switch (kind) {
                case "paper", "folia", "velocity" -> loadPaperVersions(kind);
                case "purpur" -> loadPurpurVersions();
                case "vanilla" -> loadVanillaVersions();
                case "fabric" -> loadMetaGameVersions(FABRIC_META_BASE);
                case "quilt" -> loadMetaGameVersions(QUILT_META_BASE);
                default -> List.of();
            };
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("版本列表获取失败：" + error.getMessage());
        }
    }

    private List<Map<String, String>> loadPaperVersions(String project) throws Exception {
        JsonNode body = getJson("https://fill.papermc.io/v3/projects/" + project);
        List<Map<String, String>> rows = new ArrayList<>();
        if (body == null) {
            return rows;
        }
        JsonNode versions = body.path("versions");
        if (versions.isObject()) {
            versions.fields().forEachRemaining(entry -> {
                if (entry.getValue().isArray()) {
                    for (JsonNode item : entry.getValue()) {
                        String v = item.asText("");
                        if (!v.isBlank()) {
                            rows.add(versionRow(v, isStableVersion(v)));
                        }
                    }
                }
            });
        } else if (versions.isArray()) {
            for (JsonNode item : versions) {
                String v = item.asText("");
                if (!v.isBlank()) {
                    rows.add(versionRow(v, isStableVersion(v)));
                }
            }
        }
        return rows;
    }

    private List<Map<String, String>> loadPurpurVersions() throws Exception {
        JsonNode body = getJson("https://api.purpurmc.org/v2/purpur");
        List<Map<String, String>> rows = new ArrayList<>();
        if (body == null) {
            return rows;
        }
        for (JsonNode item : body.path("versions")) {
            String v = item.asText("");
            if (!v.isBlank()) {
                rows.add(versionRow(v, isStableVersion(v)));
            }
        }
        return rows;
    }

    private List<Map<String, String>> loadVanillaVersions() throws Exception {
        JsonNode body = getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        List<Map<String, String>> rows = new ArrayList<>();
        if (body == null) {
            return rows;
        }
        for (JsonNode item : body.path("versions")) {
            String v = item.path("id").asText("");
            String type = item.path("type").asText("");
            if (v.isBlank()) {
                continue;
            }
            boolean stable = "release".equalsIgnoreCase(type);
            Map<String, String> row = versionRow(v, stable);
            row.put("type", type.isBlank() ? "-" : type);
            row.put("releaseTime", item.path("releaseTime").asText(""));
            rows.add(row);
        }
        return rows;
    }

    /** Fabric/Quilt meta 游戏版本列表（同构端点）。 */
    private List<Map<String, String>> loadMetaGameVersions(String metaBase) throws Exception {
        JsonNode body = fetchJson(metaBase + "/versions/game");
        List<Map<String, String>> rows = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return rows;
        }
        for (JsonNode item : body) {
            String v = item.path("version").asText("");
            if (v.isBlank()) {
                continue;
            }
            rows.add(versionRow(v, item.path("stable").asBoolean(false)));
        }
        return rows;
    }

    private static Map<String, String> versionRow(String version, boolean stable) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", version);
        row.put("version", version);
        row.put("label", version);
        row.put("stable", stable ? "稳定" : "测试");
        row.put("channel", stable ? "stable" : "snapshot");
        return row;
    }

    private static boolean isStableVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String lower = version.toLowerCase();
        if (lower.contains("snapshot") || lower.contains("pre") || lower.contains("rc")
                || lower.contains("w") || lower.contains("alpha") || lower.contains("beta")) {
            return false;
        }
        return version.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.');
    }

    private static String jarName(String kind, String version) {
        return kind + "-" + version + ".jar";
    }

    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String value = base.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        return value;
    }

    private JsonNode fetchJson(String url) throws Exception {
        return fetcher.fetch(url);
    }

    /** 抓取失败返回 null（用于版本兜底决策），不抛出。 */
    private JsonNode fetchJsonQuietly(String url) {
        try {
            return fetcher.fetch(url);
        } catch (Exception error) {
            return null;
        }
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        return McpanelJson.mapper().readTree(body);
    }

    private static String firstText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root.at("/" + path.replace('.', '/'));
            if (!node.isMissingNode() && !node.isNull() && !node.asText("").isBlank()) {
                return node.asText();
            }
        }
        return null;
    }
}
